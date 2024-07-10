package dev.convex.android

interface ConvexEnabled {
    fun toConvexValue() : ConvexValue
}

sealed interface ConvexData {
    class Null() : ConvexData {
        override fun equals(other: Any?): Boolean {
            return this === other
        }

        override fun hashCode(): Int {
            return System.identityHashCode(this)
        }
    }

    sealed class ConvexDataWithPayload<T>(val value: T) : ConvexData {

    }
    class Int64(value: Long) : ConvexDataWithPayload<Long>(value)

    class Float64(value: Double) : ConvexDataWithPayload<Double>(value)

    class Array(value: List<ConvexData>) : ConvexDataWithPayload<List<ConvexData>>(value)

    class String(value: kotlin.String) : ConvexDataWithPayload<kotlin.String>(value) {
        override fun toString() : kotlin.String = value
    }

    class Object(value: Map<kotlin.String, ConvexData>) : ConvexDataWithPayload<Map<kotlin.String, ConvexData>>(value) {
        override fun toString() : kotlin.String =
            value.toString()

    }

    companion object {
        fun fromFfiData(ffiData: ConvexValue) : ConvexData =
            when (ffiData.vtype) {
                ConvexValueType.NULL -> Null()
                ConvexValueType.INT64 -> Int64(ffiData.int64Value!!)
                ConvexValueType.FLOAT64 -> Float64(ffiData.float64Value!!)
                ConvexValueType.BOOLEAN -> TODO()
                ConvexValueType.STRING -> String(ffiData.stringValue!!)
                ConvexValueType.BYTES -> TODO()
                ConvexValueType.ARRAY -> Array(ffiData.arrayValue!!.map { fromFfiData(it) })
                ConvexValueType.OBJECT -> Object(ffiData.objectValue!!.mapValues { fromFfiData(it.value) })

            }
    }
}

fun ConvexValue.Companion.new(value: String) : ConvexValue = ConvexValue(vtype = ConvexValueType.STRING, stringValue = value, int64Value = null, arrayValue = null, objectValue = null, float64Value = null, boolValue = null, bytesValue = null)
fun ConvexValue.Companion.new(value: Long) : ConvexValue = ConvexValue(vtype = ConvexValueType.INT64, stringValue = null, int64Value = value, arrayValue = null, objectValue = null, float64Value = null, boolValue = null, bytesValue = null)
fun ConvexValue.Companion.new(value: Double) : ConvexValue = ConvexValue(vtype = ConvexValueType.FLOAT64, stringValue = null, int64Value = null, arrayValue = null, objectValue = null, float64Value = value, boolValue = null, bytesValue = null)
fun ConvexValue.Companion.new(value: Boolean) : ConvexValue = ConvexValue(vtype = ConvexValueType.BOOLEAN, stringValue = null, int64Value = null, arrayValue = null, objectValue = null, float64Value = null, boolValue = value, bytesValue = null)
fun ConvexValue.Companion.bytes(value: List<UByte>) : ConvexValue = ConvexValue(vtype = ConvexValueType.BYTES, stringValue = null, int64Value = null, arrayValue = null, objectValue = null, float64Value = null, boolValue = null, bytesValue = value)
fun ConvexValue.Companion.new(value: Map<String, ConvexValue>) : ConvexValue = ConvexValue(vtype = ConvexValueType.OBJECT, stringValue = null, int64Value = null, arrayValue = null, objectValue = value, float64Value = null, boolValue = null, bytesValue = null)
fun ConvexValue.Companion.new(value: List<ConvexValue>) : ConvexValue = ConvexValue(vtype = ConvexValueType.ARRAY, stringValue = null, int64Value = null, arrayValue = value, objectValue = null, float64Value = null, boolValue = null, bytesValue = null)
fun ConvexValue.Companion.empty() : ConvexValue = ConvexValue(vtype = ConvexValueType.NULL, stringValue = null, int64Value = null, arrayValue = null, objectValue = null, float64Value = null, boolValue = null, bytesValue = null)
