package dev.convex.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
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
}

@Serializable
data class Message(val author: String, val body: String)