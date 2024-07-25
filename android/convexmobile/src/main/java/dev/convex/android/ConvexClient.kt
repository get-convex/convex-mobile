package dev.convex.android

import android.util.Log
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
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
class ConvexClient(@PublishedApi internal val ffiClient: MobileConvexClientInterface) {
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
            val jsonData = ffiClient.action(
                name,
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
            val jsonData = ffiClient.mutation(
                name,
                args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf())
            return jsonApi.decodeFromString<T>(jsonData)
        } catch (e: ClientException) {
            throw ConvexException.from(e)
        }
    }
}