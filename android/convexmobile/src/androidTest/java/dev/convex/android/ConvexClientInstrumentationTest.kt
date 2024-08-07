package dev.convex.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConvexClientInstrumentationTest {

    @Test
    fun query_convex() = runTest {
        val clientA = ConvexClient("http://10.0.2.2:3210")
        val messages: List<Message>? = clientA.subscribe<List<Message>>("messages:list").first().getOrNull()
        assertNotNull(messages)
        assertEquals(messages, listOf<Message>())
    }
}

@Serializable
data class Message(val author: String, val body: String)