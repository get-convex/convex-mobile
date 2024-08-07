package dev.convex.android

import dev.convex.android.testing.FakeFfiClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import strikt.api.expectThat
import strikt.assertions.get
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isFailure
import strikt.assertions.isFalse
import strikt.assertions.isSuccess
import strikt.assertions.isTrue
import strikt.assertions.message

class ConvexClientTest {
    companion object {
        const val QUERY_NAME = "foo"
        val QUERY_ARGS = mapOf("a" to "b")
    }

    private lateinit var ffiClient: FakeFfiClient
    private lateinit var client: ConvexClient

    @Before
    fun setup() {
        ffiClient = FakeFfiClient()
        client = ConvexClient("foo://bar") { ffiClient }
    }
    @Test
    fun `subscribe adds subscription`() = runTest {
        val flowResults = mutableListOf<Foo>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { flowResults.add(it) }.onFailure { throw AssertionError() }
            }
        }

        expectThat(ffiClient.hasSubscriptionFor(QUERY_NAME, QUERY_ARGS)).isTrue()
    }

    @Test
    fun `subscribe can receive data via Flow`() = runTest {
        val flowResults = mutableListOf<Foo>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { flowResults.add(it) }.onFailure { throw AssertionError() }
            }
        }
        ffiClient.sendSubscriptionData(QUERY_NAME, QUERY_ARGS, Json.encodeToString(Foo(bar = "baz")))

        expectThat(flowResults).hasSize(1)
        expectThat(flowResults[0]).isEqualTo(Foo(bar = "baz"))
    }

    @Test
    fun `subscribe can receive multiple data via Flow`() = runTest {
        val flowResults = mutableListOf<Foo>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { flowResults.add(it) }.onFailure { throw AssertionError() }
            }
        }
        ffiClient.sendSubscriptionData(QUERY_NAME, QUERY_ARGS, Json.encodeToString(Foo(bar = "baz")))
        ffiClient.sendSubscriptionData(QUERY_NAME, QUERY_ARGS, Json.encodeToString(Foo(bar = "bar")))

        expectThat(flowResults).hasSize(2)
        expectThat(flowResults[0]).isEqualTo(Foo(bar = "baz"))
        expectThat(flowResults[1]).isEqualTo(Foo(bar = "bar"))
    }


    @Test
    fun `subscribe Flow is canceled when coroutine stops`() = runTest {
        val flowResults = mutableListOf<Foo>()

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { flowResults.add(it) }.onFailure { throw AssertionError() }
            }
        }
        job.cancel()

        expectThat(ffiClient.hasSubscriptionFor(QUERY_NAME, QUERY_ARGS)).isFalse()
    }

    @Test
    fun `subscribe Flow can receive error`() = runTest {
        var observedError: Throwable? = null

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { throw AssertionError() }.onFailure { observedError = it }
            }
        }
        ffiClient.sendSubscriptionError(QUERY_NAME, QUERY_ARGS, "an error broke out")

        expectThat(observedError).isA<ConvexException>().message.isEqualTo("an error broke out")
    }

    @Test
    fun `subscribe Flow can interleave success and errors`() = runTest {
        val flowResults = mutableListOf<Result<Foo>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                flowResults.add(result)
            }
        }
        ffiClient.sendSubscriptionData(QUERY_NAME, QUERY_ARGS, Json.encodeToString(Foo(bar = "baz")))
        ffiClient.sendSubscriptionError(QUERY_NAME, QUERY_ARGS, "an error broke out")
        ffiClient.sendSubscriptionData(QUERY_NAME, QUERY_ARGS, Json.encodeToString(Foo(bar = "baz")))

        expectThat(flowResults)[0].isSuccess()
        expectThat(flowResults)[1].isFailure()
        expectThat(flowResults)[2].isSuccess()
    }
}

@Serializable
data class Foo(val bar: String)