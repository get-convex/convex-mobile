package dev.convex.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

@PublishedApi
internal val jsonApi = Json {
    ignoreUnknownKeys = true
    allowSpecialFloatingPointValues = true
    // This allows Int, Long, Float or Double values anywhere to be annotated with @ConvexNum which
    // knows the special JSON format that Convex uses for those values.
    serializersModule =
        SerializersModule {
            contextual(Int64ToIntDecoder)
            contextual(Int64ToLongDecoder)
            contextual(Float64ToFloatDecoder)
            contextual(Float64ToDoubleDecoder)
        }
}

typealias Int64 = @Serializable(Int64ToLongDecoder::class) Long
typealias Float64 = @Serializable(Float64ToDoubleDecoder::class) Double
typealias Int32 = @Serializable(Int64ToIntDecoder::class) Int
typealias Float32 = @Serializable(Float64ToFloatDecoder::class) Float
typealias ConvexNum = Contextual

/**
 * A client API for interacting with a Convex backend.
 *
 * Handles marshalling of data between calling code and the [convex-mobile]() and
 * [convex-rs](https://github.com/get-convex/convex-rs) native libraries.
 *
 * Consumers of this client should use Kotlin's JSON serialization to handle data sent to/from the
 * Convex backend.
 */
open class ConvexClient(
    deploymentUrl: String,
    ffiClientFactory: (deploymentUrl: String) -> MobileConvexClientInterface = ::MobileConvexClient
) {

    @PublishedApi
    internal val ffiClient = ffiClientFactory(deploymentUrl)

    /**
     * Subscribes to the query with the given [name] and converts data from the subscription into a
     * [Flow] of [Result]s containing [T].
     *
     * If there is an error generating [T], the [Result] will contain either a [ConvexError] (for
     * application specific errors) or a [ServerError].
     *
     * The upstream Convex subscription will be canceled if whatever is subscribed to the [Flow]
     * stops listening.
     *
     * @param T result data type that will be decoded from JSON
     */
    inline fun <reified T> subscribe(
        name: String, args: Map<String, Any?>? = null
    ): Flow<Result<T>> = callbackFlow {
        val subscription = ffiClient.subscribe(
            name,
            args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf(),
            object : QuerySubscriber {
                override fun onUpdate(value: String) {
                    try {
                        val data = jsonApi.decodeFromString<T>(value)
                        trySend(Result.success(data))
                    } catch (e: Throwable) {
                        // Don't catch when https://github.com/mozilla/uniffi-rs/issues/2194 is fixed.
                        // Ideally any unchecked exception that happens here goes uncaught and triggers
                        // a crash, as it's likely a developer error related to mishandling the JSON
                        // data.
                        val message = "error handling data from FFI"
                        Log.e("QuerySubscriber.onUpdate", message, e)
                        cancel(message, e)
                    }
                }

                override fun onError(message: String, value: String?) {
                    if (value == null) {
                        // This is a server error of some sort.
                        trySend(Result.failure(ServerError(message)))
                    } else {
                        // An application specific error thrown in a Convex backend function.
                        trySend(Result.failure(ConvexError(message, value)))
                    }

                }
            })

        awaitClose {
            subscription.cancel()
        }
    }

    /**
     * Executes the action with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * For actions that don't return a value, prefer calling the version of this method that doesn't
     * require a return type parameter.
     *
     * @param T data type that will be decoded from JSON and returned
     */
    suspend inline fun <reified T> action(name: String, args: Map<String, Any?>? = null): T {
        try {
            val jsonData = ffiClient.action(name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            try {
                return jsonApi.decodeFromString<T>(jsonData)
            } catch (e: Throwable) {
                throw InternalError(
                    "Failed to decode JSON, ensure you're using types compatible with Convex in your return value",
                    e
                )
            }
        } catch (e: ClientException) {
            throw e.toError()
        }
    }

    /**
     * Executes the action with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * This version requires that the remote action returns null or no value. If you wish to
     * return a value from an action, use the version of the method that allows specifying the
     * return type.
     */
    @JvmName("voidAction")
    suspend fun action(name: String, args: Map<String, Any?>? = null) {
        action<Unit?>(name = name, args = args)
    }

    /**
     * Executes the mutation with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * For actions that don't return a value, prefer calling the version of this method that doesn't
     * require a return type parameter.
     *
     * @param T data type that will be decoded from JSON and returned
     */
    suspend inline fun <reified T> mutation(name: String, args: Map<String, Any?>? = null): T {
        try {
            val jsonData = ffiClient.mutation(name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            try {
                return jsonApi.decodeFromString<T>(jsonData)
            } catch (e: Throwable) {
                throw InternalError(
                    "Failed to decode JSON, ensure you're using types compatible with Convex in your return value",
                    e
                )
            }
        } catch (e: ClientException) {
            throw e.toError()
        }
    }

    /**
     * Executes the mutation with the given [name] and [args].
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * This version requires that the remote mutation returns null or no value. If you wish to
     * return a value from a mutation, use the version of the method that allows specifying the
     * return type.
     */
    @JvmName("voidMutation")
    suspend fun mutation(name: String, args: Map<String, Any?>? = null) {
        mutation<Unit?>(name = name, args = args)
    }
}

/**
 * Like [ConvexClient], but supports integration with an authentication provider via [AuthProvider].
 *
 * @param T the data returned from the [AuthProvider] upon successful authentication
 */
class ConvexClientWithAuth<T>(
    deploymentUrl: String,
    private val authProvider: AuthProvider<T>,
    ffiClientFactory: (deploymentUrl: String) -> MobileConvexClientInterface = ::MobileConvexClient
) : ConvexClient(deploymentUrl, ffiClientFactory) {
    private val _authState = MutableStateFlow<AuthState<T>>(AuthState.Unauthenticated())

    /**
     * A [Flow] of [AuthState] that represents the authentication state of this client instance.
     */
    val authState: StateFlow<AuthState<T>> = _authState

    /**
     * Triggers a UI driven login flow and updates the [authState].
     *
     * The [authState] is set to [AuthState.AuthLoading] immediately upon calling this method and
     * will change to either [AuthState.Authenticated] or [AuthState.Unauthenticated] depending on
     * the result.
     */
    suspend fun login(context: Context): Result<T> {
        _authState.emit(AuthState.AuthLoading())
        val result = authProvider.login(context)
        return result.onSuccess {
            ffiClient.setAuth(authProvider.extractIdToken(it))
            _authState.emit(AuthState.Authenticated(it))
        }
            .onFailure { _authState.emit(AuthState.Unauthenticated()) }
    }

    /**
     * Triggers a cached, UI-less re-authentication flow using previously stored credentials and
     * updates the [authState].
     *
     * If no credentials were previously stored, or if there is an error reusing stored credentials,
     * the resulting [authState] will be [AuthState.Unauthenticated]. If supported by the
     * [AuthProvider], a call to [login] should store another set of credentials upon successful
     * authentication.
     *
     * The [authState] is set to [AuthState.AuthLoading] immediately upon calling this method and
     * will change to either [AuthState.Authenticated] or [AuthState.Unauthenticated] depending on
     * the result.
     *
     * @throws NotImplementedError if the [AuthProvider] doesn't support [loginFromCache]
     */
    suspend fun loginFromCache(): Result<T> {
        _authState.emit(AuthState.AuthLoading())
        val result = authProvider.loginFromCache()
        return result.onSuccess {
            ffiClient.setAuth(authProvider.extractIdToken(it))
            _authState.emit(AuthState.Authenticated(result.getOrThrow()))
        }
            .onFailure { _authState.emit(AuthState.Unauthenticated()) }
    }

    /**
     * Triggers a logout flow and updates the [authState].
     *
     * The [authState] will change to [AuthState.Unauthenticated] if logout is successful.
     */
    suspend fun logout(context: Context): Result<Void?> {
        val result = authProvider.logout(context)
        if (result.isSuccess) {
            ffiClient.setAuth(null)
            _authState.emit(AuthState.Unauthenticated())
        }
        return result
    }
}

/**
 * Authentication states that can be experienced when using an [AuthProvider] with
 * [ConvexClientWithAuth].
 */
sealed class AuthState<T> {
    /**
     * The state that represents an authenticated user.
     *
     * Provides [userInfo] for consumers.
     */
    data class Authenticated<T>(val userInfo: T) : AuthState<T>()

    /**
     * The state that represents an unauthenticated user.
     */
    class Unauthenticated<T> : AuthState<T>()

    /**
     * The state that represents an ongoing authentication attempt.
     */
    class AuthLoading<T> : AuthState<T>()
}

/**
 * An authentication provider, used with [ConvexClientWithAuth].
 *
 * @param T the type of data included upon a successful authentication attempt
 */
interface AuthProvider<T> {
    /**
     * Trigger a login flow, which might launch a new UI/screen.
     *
     * @param context typically an Activity which can be used to launch login UI
     * @return a [Result] containing [T] upon successful login
     */
    suspend fun login(context: Context): Result<T>

    /**
     * Trigger a cached, UI-less re-authentication using stored credentials from a previous [login].
     *
     * @throws NotImplementedError if this provider doesn't support [loginFromCache]
     * @return a [Result] containing [T] upon successful re-auth
     */
    suspend fun loginFromCache(): Result<T> {
        throw NotImplementedError("$this does not support loginFromCache")
    }

    /**
     * Trigger a logout flow, which might launch a new screen/UI.
     *
     * @param context typically an Activity which can be used to launch logout UI
     * @return a [Result] which indicates whether logout was successful
     */
    suspend fun logout(context: Context): Result<Void?>

    /**
     * Extracts a [JWT ID token](https://openid.net/specs/openid-connect-core-1_0.html#IDToken) from
     * the [T] payload.
     *
     * The [T] payload is returned from a successful [login] attempt.
     */
    fun extractIdToken(authResult: T): String
}