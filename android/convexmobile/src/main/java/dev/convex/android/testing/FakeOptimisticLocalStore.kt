package dev.convex.android.testing

import dev.convex.android.ClientException
import dev.convex.android.MobileOptimisticLocalStore
import dev.convex.android.MobileOptimisticQuery
import dev.convex.android.NoPointer

/**
 * An in-memory [MobileOptimisticLocalStore] for tests, keyed like the Rust store by query name and
 * JSON-encoded args.
 */
class FakeOptimisticLocalStore : MobileOptimisticLocalStore(NoPointer) {
    /** Query results by query; `null` values are loading. */
    val queries = mutableMapOf<CallKey, String?>()

    /** If set, the next store call throws this instead of running. */
    var nextError: ClientException? = null

    private fun throwNextError() {
        nextError?.let {
            nextError = null
            throw it
        }
    }

    override fun getQuery(name: String, args: Map<String, String>): String? {
        throwNextError()
        return queries[CallKey(name, args)]
    }

    override fun getAllQueries(name: String): List<MobileOptimisticQuery> {
        throwNextError()
        return queries.filterKeys { it.name == name }
            .map { (key, value) -> MobileOptimisticQuery(key.args, value) }
    }

    override fun setQuery(name: String, args: Map<String, String>, value: String?) {
        throwNextError()
        queries[CallKey(name, args)] = value
    }
}
