package dev.convex.android.testing

import dev.convex.android.MobileConvexClientInterface
import dev.convex.android.NoPointer
import dev.convex.android.QuerySubscriber
import dev.convex.android.SubscriptionHandle
import dev.convex.android.toJsonElement

class FakeFfiClient : MobileConvexClientInterface {
    val subscriptions = mutableMapOf<CallKey, QuerySubscriber>()

    override suspend fun action(name: String, args: Map<String, String>): String {
        TODO("Not yet implemented")
    }

    override suspend fun mutation(name: String, args: Map<String, String>): String {
        TODO("Not yet implemented")
    }

    override suspend fun query(name: String, args: Map<String, String>): String {
        TODO("Not yet implemented")
    }

    override suspend fun setAuth(token: String?) {
        TODO("Not yet implemented")
    }

    override suspend fun subscribe(
        name: String,
        args: Map<String, String>,
        subscriber: QuerySubscriber
    ): SubscriptionHandle {
        val subscriptionKey = CallKey(name, args)
        subscriptions[subscriptionKey] = subscriber
        return object : SubscriptionHandle(NoPointer) {
            override fun cancel() {
                subscriptions.remove(subscriptionKey)
            }
        }
    }

    fun sendSubscriptionData(name: String, args: Map<String, Any>, data: String) {
        subscriptions[CallKey(name, args.mapValues { it.value.toJsonElement().toString() })]!!.onUpdate(data)
    }

    fun sendSubscriptionError(name: String, args: Map<String, Any>, errorMessage: String) {
        subscriptions[CallKey(name, args.mapValues { it.value.toJsonElement().toString() })]!!.onError(errorMessage, null)
    }

    fun hasSubscriptionFor(name: String, args: Map<String, Any>) = subscriptions.contains(CallKey(name, args.mapValues { it.value.toJsonElement().toString() }))
}

data class CallKey(val name: String, val args: Map<String, String>)