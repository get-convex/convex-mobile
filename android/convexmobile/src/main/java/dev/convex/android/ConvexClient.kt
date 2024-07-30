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
abstract class BaseConvexClient(@PublishedApi internal val ffiClient: MobileConvexClientInterface) {
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
        name: String
    ): Flow<Result<T>> = callbackFlow {
        val subscription = ffiClient.subscribe(name, object : QuerySubscriber {
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

class ConvexClient(ffiClient: MobileConvexClientInterface) : BaseConvexClient(ffiClient)

class ConvexClientWithAuth<T>(
    ffiClient: MobileConvexClientInterface, private val authProvider: AuthProvider<T>
) : BaseConvexClient(ffiClient) {
    private val _authState = MutableStateFlow<AuthState<T>>(AuthState.Unauthenticated())
    val authState: StateFlow<AuthState<T>> = _authState

    suspend fun login(context: Context): Result<T> {
        _authState.emit(AuthState.AuthLoading())
        val result = authProvider.login(context)
        return result.onSuccess { _authState.emit(AuthState.Authenticated(result.getOrThrow())) }
            .onFailure { _authState.emit(AuthState.Unauthenticated()) }
    }

    suspend fun logout(context: Context): Result<Void?> {
        val result = authProvider.logout(context)
        if (result.isSuccess) {
            _authState.emit(AuthState.Unauthenticated())
        }
        return result
    }
}

sealed class AuthState<T> {
    data class Authenticated<T>(val userInfo: T) : AuthState<T>()
    class Unauthenticated<T> : AuthState<T>()
    class AuthLoading<T> : AuthState<T>()
}

interface AuthProvider<T> {
    suspend fun login(context: Context): Result<T>
    suspend fun logout(context: Context): Result<Void?>
}