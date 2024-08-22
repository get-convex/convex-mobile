package dev.convex.android

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@PublishedApi
internal fun Map<*, *>.toJsonElement(): JsonElement {
    val map: MutableMap<String, JsonElement> = mutableMapOf()
    this.forEach {
        val key = it.key as? String ?: return@forEach
        val value = it.value ?: return@forEach
        when (value) {
            is Map<*, *> -> map[key] = (value).toJsonElement()
            is List<*> -> map[key] = value.toJsonElement()
            else -> map[key] = value.toJsonElement()
        }
    }
    return JsonObject(map)
}

@PublishedApi
internal fun List<*>.toJsonElement(): JsonElement {
    val list: MutableList<JsonElement> = mutableListOf()
    this.forEach {
        val value = it ?: return@forEach
        when (value) {
            is Map<*, *> -> list.add((value).toJsonElement())
            is List<*> -> list.add(value.toJsonElement())
            else -> list.add(value.toJsonElement())
        }
    }
    return JsonArray(list)
}

@PublishedApi
internal fun Any?.toJsonElement(): JsonElement {
    return when (this) {
        is Map<*, *> -> this.toJsonElement()
        is List<*> -> this.toJsonElement()
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        null -> JsonNull
        else -> throw IllegalArgumentException("only maps, lists and JSON primitives supported; got $this")
    }
}