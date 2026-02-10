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
        val QUERY_ARGS = mapOf(
            "a" to "b",
            "b" to false,
            "c" to 42,
            "d" to listOf(1, 2, 3),
            "e" to mapOf("foo" to "bar", "baz" to 42),
            "f" to null
        )
    }

    private lateinit var ffiClient: FakeFfiClient
    private lateinit var client: ConvexClient

    @Before
    fun setup() {
        ffiClient = FakeFfiClient()
        client = ConvexClient("foo://bar") { _, _, _ -> ffiClient }
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
    fun `subscribe properly encodes args`() = runTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect {}
        }

        val encodedArgs = ffiClient.subscriptionRequestsFor(QUERY_NAME).single().args
        expectThat(encodedArgs["a"]!!).isEqualTo("\"b\"")
        expectThat(encodedArgs["b"]!!).isEqualTo("false")
        expectThat(encodedArgs["c"]!!).isEqualTo("{\"\$integer\":\"KgAAAAAAAAA=\"}")
        expectThat(encodedArgs["d"]!!).isEqualTo("[{\"\$integer\":\"AQAAAAAAAAA=\"},{\"\$integer\":\"AgAAAAAAAAA=\"},{\"\$integer\":\"AwAAAAAAAAA=\"}]")
        expectThat(encodedArgs["e"]!!).isEqualTo("{\"foo\":\"bar\",\"baz\":{\"\$integer\":\"KgAAAAAAAAA=\"}}")
        expectThat(encodedArgs["f"]!!).isEqualTo("null")
    }

    @Test
    fun `subscribe preserves nested nulls in maps and lists`() = runTest {
        val nestedArgs = mapOf(
            "paginationOpts" to mapOf(
                "numItems" to 10,
                "cursor" to null
            ),
            "items" to listOf(1, null, mapOf("k" to null))
        )

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, nestedArgs).collect {}
        }

        val encodedArgs = ffiClient.subscriptionRequestsFor(QUERY_NAME).single().args
        expectThat(encodedArgs["paginationOpts"]!!).isEqualTo("{\"numItems\":{\"\$integer\":\"CgAAAAAAAAA=\"},\"cursor\":null}")
        expectThat(encodedArgs["items"]!!).isEqualTo("[{\"\$integer\":\"AQAAAAAAAAA=\"},null,{\"k\":null}]")
    }

    @Test
    fun `mutation properly encodes args`() = runTest {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.mutation(QUERY_NAME, QUERY_ARGS)
        }

        job.join()

        val encodedArgs = ffiClient.mutations[QUERY_NAME]!!
        expectThat(encodedArgs["a"]!!).isEqualTo("\"b\"")
        expectThat(encodedArgs["b"]!!).isEqualTo("false")
        expectThat(encodedArgs["c"]!!).isEqualTo("{\"\$integer\":\"KgAAAAAAAAA=\"}")
        expectThat(encodedArgs["d"]!!).isEqualTo("[{\"\$integer\":\"AQAAAAAAAAA=\"},{\"\$integer\":\"AgAAAAAAAAA=\"},{\"\$integer\":\"AwAAAAAAAAA=\"}]")
        expectThat(encodedArgs["e"]!!).isEqualTo("{\"foo\":\"bar\",\"baz\":{\"\$integer\":\"KgAAAAAAAAA=\"}}")
        expectThat(encodedArgs["f"]!!).isEqualTo("null")
    }

    @Test
    fun `mutation preserves nested nulls in maps and lists`() = runTest {
        val nestedArgs = mapOf(
            "paginationOpts" to mapOf(
                "numItems" to 10,
                "cursor" to null
            ),
            "items" to listOf(1, null, mapOf("k" to null))
        )

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.mutation<Unit?>(QUERY_NAME, nestedArgs)
        }

        job.join()

        val encodedArgs = ffiClient.mutations[QUERY_NAME]!!
        expectThat(encodedArgs["paginationOpts"]!!).isEqualTo("{\"numItems\":{\"\$integer\":\"CgAAAAAAAAA=\"},\"cursor\":null}")
        expectThat(encodedArgs["items"]!!).isEqualTo("[{\"\$integer\":\"AQAAAAAAAAA=\"},null,{\"k\":null}]")
    }

    @Test
    fun `action properly encodes args`() = runTest {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.action(QUERY_NAME, QUERY_ARGS)
        }

        job.join()

        val encodedArgs = ffiClient.actions[QUERY_NAME]!!
        expectThat(encodedArgs["a"]!!).isEqualTo("\"b\"")
        expectThat(encodedArgs["b"]!!).isEqualTo("false")
        expectThat(encodedArgs["c"]!!).isEqualTo("{\"\$integer\":\"KgAAAAAAAAA=\"}")
        expectThat(encodedArgs["d"]!!).isEqualTo("[{\"\$integer\":\"AQAAAAAAAAA=\"},{\"\$integer\":\"AgAAAAAAAAA=\"},{\"\$integer\":\"AwAAAAAAAAA=\"}]")
        expectThat(encodedArgs["e"]!!).isEqualTo("{\"foo\":\"bar\",\"baz\":{\"\$integer\":\"KgAAAAAAAAA=\"}}")
        expectThat(encodedArgs["f"]!!).isEqualTo("null")
    }

    @Test
    fun `action preserves nested nulls in maps and lists`() = runTest {
        val nestedArgs = mapOf(
            "paginationOpts" to mapOf(
                "numItems" to 10,
                "cursor" to null
            ),
            "items" to listOf(1, null, mapOf("k" to null))
        )

        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.action<Unit?>(QUERY_NAME, nestedArgs)
        }

        job.join()

        val encodedArgs = ffiClient.actions[QUERY_NAME]!!
        expectThat(encodedArgs["paginationOpts"]!!).isEqualTo("{\"numItems\":{\"\$integer\":\"CgAAAAAAAAA=\"},\"cursor\":null}")
        expectThat(encodedArgs["items"]!!).isEqualTo("[{\"\$integer\":\"AQAAAAAAAAA=\"},null,{\"k\":null}]")
    }

    @Test
    fun `subscribe can receive data via Flow`() = runTest {
        val flowResults = mutableListOf<Foo>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { flowResults.add(it) }.onFailure { throw AssertionError() }
            }
        }
        ffiClient.sendSubscriptionData(
            QUERY_NAME,
            QUERY_ARGS,
            Json.encodeToString(Foo(bar = "baz"))
        )

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
        ffiClient.sendSubscriptionData(
            QUERY_NAME,
            QUERY_ARGS,
            Json.encodeToString(Foo(bar = "baz"))
        )
        ffiClient.sendSubscriptionData(
            QUERY_NAME,
            QUERY_ARGS,
            Json.encodeToString(Foo(bar = "bar"))
        )

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
    fun `subscribe Flow can receive ServerError`() = runTest {
        var observedError: Throwable? = null

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { throw AssertionError() }.onFailure { observedError = it }
            }
        }
        ffiClient.sendSubscriptionError(QUERY_NAME, QUERY_ARGS, "an error broke out")

        expectThat(observedError).isA<ServerError>().message.isEqualTo("an error broke out")
    }


    @Test
    fun `subscribe Flow can receive ConvexError`() = runTest {
        var observedError: Throwable? = null

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                result.onSuccess { throw AssertionError() }.onFailure { observedError = it }
            }
        }
        // Including errorData triggers a ConvexError.
        ffiClient.sendSubscriptionError(
            QUERY_NAME,
            QUERY_ARGS,
            "an error broke out",
            errorData = "some error data"
        )

        expectThat(observedError).isA<ConvexError>().message.isEqualTo("an error broke out")
        expectThat(observedError).isA<ConvexError>().get(ConvexError::data)
            .isEqualTo("some error data")
    }

    @Test
    fun `subscribe Flow can interleave success and errors`() = runTest {
        val flowResults = mutableListOf<Result<Foo>>()

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            client.subscribe<Foo>(QUERY_NAME, QUERY_ARGS).collect { result ->
                flowResults.add(result)
            }
        }
        ffiClient.sendSubscriptionData(
            QUERY_NAME,
            QUERY_ARGS,
            Json.encodeToString(Foo(bar = "baz"))
        )
        ffiClient.sendSubscriptionError(QUERY_NAME, QUERY_ARGS, "an error broke out")
        ffiClient.sendSubscriptionData(
            QUERY_NAME,
            QUERY_ARGS,
            Json.encodeToString(Foo(bar = "baz"))
        )

        expectThat(flowResults)[0].isSuccess()
        expectThat(flowResults)[1].isFailure()
        expectThat(flowResults)[2].isSuccess()
    }
}

@Serializable
data class Foo(val bar: String)
