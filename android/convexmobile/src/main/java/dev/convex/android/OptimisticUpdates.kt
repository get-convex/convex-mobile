package dev.convex.android

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

/**
 * A temporary, local update to query results, applied while its mutation is in flight.
 *
 * The update runs when the mutation is called and is rolled back once the mutation's result is
 * reflected in query results from the server, or the mutation fails. It's replayed on top of new
 * server data until then, so it may run several times and must be deterministic.
 *
 * It runs synchronously on the client's background thread: it must be fast, must not block and
 * must not call back into the client. It's kept until its mutation completes, which may be
 * indefinitely while offline, so avoid capturing large objects.
 *
 * If it throws, its writes are undone. On the first run the mutation isn't sent and the exception
 * is rethrown from the mutation call; on a replay the update is dropped.
 */
typealias OptimisticUpdate = (store: OptimisticLocalStore) -> Unit

/**
 * A query result as seen by an [OptimisticUpdate].
 *
 * @property args the query's arguments, in Convex's JSON encoding
 * @property value the query's result, or null if it's loading
 */
data class OptimisticQuery<T>(val args: Map<String, JsonElement>, val value: T?)

/**
 * A view of the query results in the client for use within an [OptimisticUpdate].
 *
 * Queries are identified by their name and args, which must match the args used to subscribe. The
 * store can only be used while the update runs.
 */
class OptimisticLocalStore @PublishedApi internal constructor(
    @PublishedApi internal val ffiStore: MobileOptimisticLocalStore
) {
    /**
     * Returns the result of the query with the given [name] and [args], or null if it isn't loaded,
     * is loading or failed.
     */
    inline fun <reified T> getQuery(name: String, args: Map<String, Any?>? = null): T? =
        mapErrors { ffiStore.getQuery(name, encodeArgs(args)) }
            ?.let { jsonApi.decodeFromString<T>(it) }

    /**
     * Returns the args and results of all loaded queries with the given [name], for example every
     * page of a paginated list.
     */
    inline fun <reified T> getAllQueries(name: String): List<OptimisticQuery<T>> =
        mapErrors { ffiStore.getAllQueries(name) }.map { query ->
            OptimisticQuery(
                query.args.mapValues { jsonApi.parseToJsonElement(it.value) },
                query.value?.let { jsonApi.decodeFromString<T>(it) })
        }

    /**
     * Optimistically sets the result of the query with the given [name] and [args] to [value].
     *
     * The value is encoded the same way subscription results are decoded, so a value read with
     * [getQuery] and modified can be set back. Annotate values that are Int64 in Convex with
     * [Int64] or [ConvexNum].
     */
    inline fun <reified T> setQuery(name: String, args: Map<String, Any?>? = null, value: T) =
        mapErrors { ffiStore.setQuery(name, encodeArgs(args), jsonApi.encodeToString(value)) }

    /**
     * Optimistically sets the query with the given [name] and [args] to loading. Subscribers don't
     * receive a value for it until the next result from the server.
     */
    fun setQueryLoading(name: String, args: Map<String, Any?>? = null) =
        mapErrors { ffiStore.setQuery(name, encodeArgs(args), null) }

    /** Throws the same errors as the rest of the client rather than raw FFI exceptions. */
    @PublishedApi
    internal inline fun <R> mapErrors(block: () -> R): R =
        try {
            block()
        } catch (e: ClientException) {
            throw e.toError()
        }

    @PublishedApi
    internal fun encodeArgs(args: Map<String, Any?>?): Map<String, String> =
        args?.mapValues { it.value.toJsonElement().toString() } ?: mapOf()
}

@PublishedApi
internal class OptimisticUpdateAdapter(private val update: OptimisticUpdate) :
    MobileOptimisticUpdate {
    private var hasRun = false

    /** What the update threw on its first run, to rethrow from the mutation call. */
    @Volatile
    @PublishedApi
    internal var firstRunError: Throwable? = null
        private set

    @Synchronized
    override fun apply(store: MobileOptimisticLocalStore) {
        val isFirstRun = !hasRun
        hasRun = true
        try {
            update(OptimisticLocalStore(store))
        } catch (e: Throwable) {
            if (isFirstRun) {
                firstRunError = e
            }
            // Only ClientExceptions cross the FFI as errors; anything else would crash.
            throw ClientException.InternalException(
                "Optimistic update failed: ${e.message ?: e.javaClass.name}")
        }
    }
}
