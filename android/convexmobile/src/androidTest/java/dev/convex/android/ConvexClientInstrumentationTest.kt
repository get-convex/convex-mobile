@file:UseSerializers(
    Int64ToIntDecoder::class,
    Int64ToLongDecoder::class,
    Float64ToFloatDecoder::class,
    Float64ToDoubleDecoder::class
)

package dev.convex.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val DEPLOYMENT_URL = "https://curious-lynx-309.convex.cloud"

@RunWith(AndroidJUnit4::class)
class ConvexClientInstrumentationTest {
    @Before
    fun setup() {
        runBlocking {
            val client = ConvexClient(DEPLOYMENT_URL)
            client.mutation<Unit?>("messages:clearAll")
        }
    }

    @Test
    fun empty_subscribe() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val messages: List<Message>? =
            clientA.subscribe<List<Message>>("messages:list").first().getOrNull()
        assertNotNull(messages)
        assertEquals(messages, listOf<Message>())
    }

    @Test
    fun convex_error_in_subscription() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val observedError =
            clientA.subscribe<List<Message>>("messages:list", mapOf("forceError" to true)).first()
                .exceptionOrNull() as ConvexError?
        assertNotNull(observedError)
        assertEquals("forced error data", Json.decodeFromString<String>(observedError!!.data))
    }

    @Test
    fun convex_error_in_action() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        var caught = false
        try {
            clientA.action("messages:forceActionError")
        } catch (e: ConvexError) {
            caught = true
            assertEquals("forced error data", Json.decodeFromString<String>(e.data))
        } finally {
            assertEquals(true, caught)
        }
    }

    @Test
    fun convex_error_in_mutation() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        var caught = false
        try {
            clientA.mutation("messages:forceMutationError")
        } catch (e: ConvexError) {
            caught = true
            assertEquals("forced error data", Json.decodeFromString<String>(e.data))
        } finally {
            assertEquals(true, caught)
        }
    }

    @Test
    fun send_and_receive_one_message() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val clientB = ConvexClient(DEPLOYMENT_URL)

        val messagesA: Flow<Result<List<Message>>> =
            clientA.subscribe<List<Message>>("messages:list")

        clientB.mutation<Unit?>(
            "messages:send",
            mapOf("author" to "Client B", "body" to "Test 123")
        )

        val subscriptionResult = messagesA.first()

        subscriptionResult.onSuccess { messages ->
            assertEquals(messages.size, 1)
            assertEquals(messages[0].body, "Test 123")
            assertEquals(messages[0].author, "Client B")
        }.onFailure { throw AssertionError() }
    }

    @Test
    fun send_and_receive_multiple_messages() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val clientB = ConvexClient(DEPLOYMENT_URL)

        val receivedA = mutableListOf<List<Message>>()


        val receiveJob = launch {
            clientA.subscribe<List<Message>>("messages:list").take(4).collect { messages ->
                receivedA.add(messages.getOrThrow())
            }
        }

        launch {
            for (i in 1..3) {
                clientB.mutation<Unit?>(
                    "messages:send",
                    mapOf("author" to "Client B", "body" to "Message #$i")
                )
            }
        }

        receiveJob.join()

        assertEquals(4, receivedA.size)
        assertEquals(listOf<Message>(), receivedA[0])
        assertEquals(listOf(Message(author = "Client B", body = "Message #1")), receivedA[1])
        assertEquals(
            listOf(
                Message(author = "Client B", body = "Message #1"),
                Message(author = "Client B", body = "Message #2")
            ), receivedA[2]
        )
        assertEquals(
            listOf(
                Message(author = "Client B", body = "Message #1"),
                Message(author = "Client B", body = "Message #2"),
                Message(author = "Client B", body = "Message #3")
            ), receivedA[3]
        )

    }

    @Test
    fun can_round_trip_max_value_args() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val maxValues = NumericValues(
            anInt64 = Long.MAX_VALUE,
            aFloat64 = Double.MAX_VALUE,
            jsNumber = Double.MAX_VALUE,
            aFloat32 = Float.MAX_VALUE,
            anInt32 = Int.MAX_VALUE,
        )
        val result = clientA.action<NumericValues>(
            "messages:echoValidatedArgs",
            args = maxValues.toArgs()
        )
        assertEquals(maxValues, result)
    }

    @Test
    fun can_round_trip_nulls() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val sentArgs = mapOf("anotherInt32" to null)
        val receivedArgs = clientA.action<Map<String, Int32?>>(
            "messages:echoNullableArgs",
            args = sentArgs
        )
        assertEquals(sentArgs, receivedArgs)
    }

    @Test
    fun can_round_trip_with_special_floats() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val result = clientA.action<SpecialFloats>(
            "messages:echoArgs",
            args = SpecialFloats().toArgs()
        )
        assertEquals(SpecialFloats(), result)
    }

    @Test
    fun can_receive_special_floats() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        // Using @ConvexNum to allow a Double in a Map return value.
        val result = clientA.action<Map<String, @ConvexNum Double>>("messages:specialFloats")
        assertEquals(Double.NaN, result["NaN"])
        assertEquals(-0.0, result["negZero"])
        assertEquals(Double.POSITIVE_INFINITY, result["Inf"])
        assertEquals(Double.NEGATIVE_INFINITY, result["negInf"])
    }

    @Test
    fun can_receive_numbers() = runTest {
        val clientA = ConvexClient(DEPLOYMENT_URL)
        val result = clientA.action<NumericValues>("messages:numbers")
        val expected = NumericValues(
            anInt64 = 100,
            aFloat64 = 100.0,
            jsNumber = 100.0,
            anInt32 = 100,
            aFloat32 = 100.0f
        )
        assertEquals(expected, result)
        assertEquals(100, result.aPlainInt)
    }

    @Test
    fun can_observe_websocket_state() = runTest {
        val client = ConvexClient(DEPLOYMENT_URL)

        val states = mutableListOf<WebSocketState>()

        val receiveJob = launch {
            client.webSocketStateFlow.take(2).collect { state ->
                states.add(state)
            }
        }

        // It doesn't really matter which Convex function we call - but calling one should trigger
        // a connection.
        client.mutation<Unit?>("messages:clearAll")

        receiveJob.join()

        assertEquals(2, states.size)
        assertEquals(listOf(WebSocketState.CONNECTING, WebSocketState.CONNECTED), states)
    }
}

@Serializable
data class Message(val author: String, val body: String)

// This class uses a mixture of Convex type aliases and builtin types.
// The builtin types can be handled thanks to the @file:UseSerializers annotation at the top.
@Serializable
data class NumericValues(
    val anInt64: Int64,
    val aFloat64: @Serializable(Float64ToDoubleDecoder::class) Double,
    @SerialName("aPlainInt") private val jsNumber: Double,
    val anInt32: Int32,
    val aFloat32: Float,
) {
    fun toArgs(): Map<String, Any?> =
        mapOf(
            "anInt64" to anInt64,
            "aFloat64" to aFloat64,
            "aPlainInt" to jsNumber,
            "anInt32" to anInt32,
            "aFloat32" to aFloat32
        )

    // Expose the JavaScript number value as an Int.
    val aPlainInt get() = jsNumber.toInt()
}

@Serializable
data class SpecialFloats(
    val f64Nan: Float64 = Float64.NaN,
    val f64NegInf: Double = Double.NEGATIVE_INFINITY,
    val f64PosInf: Float64 = Float64.POSITIVE_INFINITY,
    val f32Nan: Float32 = Float32.NaN,
    val f32NegInf: Float = Float.NEGATIVE_INFINITY,
    val f32PosInf: Float32 = Float32.POSITIVE_INFINITY,
) {
    fun toArgs(): Map<String, Any> =
        // TODO figure out why sending the NaN values to the Rust side panics
        mapOf(
//            "f64Nan" to f64Nan,
            "f64NegInf" to f64NegInf,
            "f64PosInf" to f64PosInf,
//            "f32Nan" to f32Nan,
            "f32NegInf" to f32NegInf,
            "f32PosInf" to f32PosInf,
        )
}