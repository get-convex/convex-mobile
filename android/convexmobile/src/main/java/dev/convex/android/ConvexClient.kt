package dev.convex.android

import android.content.Context
import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

@PublishedApi
internal val jsonApi = Json { ignoreUnknownKeys = true }

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
     * Will cancel the upstream subscription if whatever is subscribed to the [Flow] stops
     * listening.
     *
     * @param T result data type that will be decoded from JSON
     */
    suspend inline fun <reified T> subscribe(
        name: String, args: Map<String, Any>? = null
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
                    trySend(Result.failure(ConvexException(message)))
                }
            })

        awaitClose {
            subscription?.cancel()
        }
    }

    /**
     * Executes the action with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * For actions that don't return a value, use `Unit?` as the [T] parameter.
     *
     * @param T data type that will be decoded from JSON and returned
     */
    suspend inline fun <reified T> action(name: String, args: Map<String, Any>? = null): T {
        try {
            val jsonData = ffiClient.action(name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            return jsonApi.decodeFromString<T>(jsonData)
        } catch (e: ClientException) {
            throw ConvexException.from(e)
        }
    }

    /**
     * Executes the mutation with the given [name] and [args] and returns the result.
     *
     * The [args] should be a map of [String] to any data that can be serialized to JSON.
     *
     * For actions that don't return a value, use `Unit?` as the [T] parameter.
     *
     * @param T data type that will be decoded from JSON and returned
     */
    suspend inline fun <reified T> mutation(name: String, args: Map<String, Any>? = null): T {
        try {
            val jsonData = ffiClient.mutation(name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            return jsonApi.decodeFromString<T>(jsonData)
        } catch (e: ClientException) {
            throw ConvexException.from(e)
        }
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
     * Triggers a login flow and updates the [authState].
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
 *
 * @param T the type of data included upon a successful authentication attempt
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
     * @return a [Result] containing [T] upon successful login
     */
    suspend fun login(context: Context): Result<T>

    /**
     * Trigger a logout flow, which might launch a new screen/UI.
     *
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