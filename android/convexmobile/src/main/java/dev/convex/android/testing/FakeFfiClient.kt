package dev.convex.android.testing

import dev.convex.android.MobileConvexClientInterface
import dev.convex.android.NoPointer
import dev.convex.android.QuerySubscriber
import dev.convex.android.SubscriptionHandle
import dev.convex.android.toJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FakeFfiClient : MobileConvexClientInterface {
    val subscriptions = mutableMapOf<CallKey, QuerySubscriber>()
    val actions = mutableMapOf<String, Map<String, String>>()
    val mutations = mutableMapOf<String, Map<String, String>>()

    override suspend fun action(name: String, args: Map<String, String>): String {
        actions[name] = args
        return Json.encodeToString<Unit?>(null)
    }

    override suspend fun mutation(name: String, args: Map<String, String>): String {
        mutations[name] = args
        return Json.encodeToString<Unit?>(null)
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
        subscriptions[CallKey(
            name,
            args.mapValues { it.value.toJsonElement().toString() })]!!.onUpdate(data)
    }

    fun sendSubscriptionError(name: String, args: Map<String, Any>, errorMessage: String) {
        subscriptions[CallKey(
            name,
            args.mapValues { it.value.toJsonElement().toString() })]!!.onError(errorMessage, null)
    }

    fun hasSubscriptionFor(name: String, args: Map<String, Any>) = subscriptions.contains(
        CallKey(
            name,
            args.mapValues { it.value.toJsonElement().toString() })
    )

    fun subscriptionRequestsFor(name: String): Iterable<CallKey> {
        return subscriptions.keys.filter { key -> key.name == name }
    }
}

data class CallKey(val name: String, val args: Map<String, String>)