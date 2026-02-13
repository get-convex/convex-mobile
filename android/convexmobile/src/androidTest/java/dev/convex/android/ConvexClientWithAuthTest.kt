package dev.convex.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.convex.android.testing.FakeFfiClient
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConvexClientWithAuthTest {

    private fun TestScope.createClient(
        ffiClient: FakeFfiClient = FakeFfiClient(),
        authProvider: FakeAuthProvider = FakeAuthProvider()
    ): Triple<ConvexClientWithAuth<FakeCredentials>, FakeFfiClient, FakeAuthProvider> {
        val client = ConvexClientWithAuth(
            "foo://bar",
            authProvider,
            coroutineScope = this
        ) { _, _, _ -> ffiClient }
        return Triple(client, ffiClient, authProvider)
    }

    @Test
    fun login_success_publishes_correct_states() = runTest {
        val (client, _, _) = createClient()
        val flowResults = mutableListOf<AuthState<FakeCredentials>>()

        val consumer = launch {
            client.authState.take(3).collect { flowResults.add(it) }
        }
        launch {
            client.login(ApplicationProvider.getApplicationContext())
        }
        consumer.join()

        assertTrue(flowResults[0] is AuthState.Unauthenticated)
        assertTrue(flowResults[1] is AuthState.AuthLoading)
        assertTrue(flowResults[2] is AuthState.Authenticated)
    }

    @Test
    fun loginFromCache_success_publishes_correct_states() = runTest {
        val (client, _, _) = createClient()
        val flowResults = mutableListOf<AuthState<FakeCredentials>>()

        val consumer = launch {
            client.authState.take(3).collect { flowResults.add(it) }
        }
        launch {
            client.loginFromCache()
        }
        consumer.join()

        assertTrue(flowResults[0] is AuthState.Unauthenticated)
        assertTrue(flowResults[1] is AuthState.AuthLoading)
        assertTrue(flowResults[2] is AuthState.Authenticated)
    }

    @Test
    fun logout_success_publishes_correct_states() = runTest {
        val (client, _, _) = createClient()
        val flowResults = mutableListOf<AuthState<FakeCredentials>>()

        val consumer = launch {
            client.authState.take(4).collect { flowResults.add(it) }
        }
        launch {
            client.login(ApplicationProvider.getApplicationContext())
            client.logout(ApplicationProvider.getApplicationContext())
        }
        consumer.join()

        assertTrue(flowResults[3] is AuthState.Unauthenticated)
    }

    @Test
    fun login_failure_publishes_correct_states() = runTest {
        val authProvider = FakeAuthProvider().apply { simulateFailure = true }
        val (client, _, _) = createClient(authProvider = authProvider)
        val flowResults = mutableListOf<AuthState<FakeCredentials>>()

        val consumer = launch {
            client.authState.take(3).collect { flowResults.add(it) }
        }
        launch {
            client.login(ApplicationProvider.getApplicationContext())
        }
        consumer.join()

        assertTrue(flowResults[0] is AuthState.Unauthenticated)
        assertTrue(flowResults[1] is AuthState.AuthLoading)
        assertTrue(flowResults[2] is AuthState.Unauthenticated)
    }

    @Test
    fun loginFromCache_failure_publishes_correct_states() = runTest {
        val authProvider = FakeAuthProvider().apply { simulateFailure = true }
        val (client, _, _) = createClient(authProvider = authProvider)
        val flowResults = mutableListOf<AuthState<FakeCredentials>>()

        val consumer = launch {
            client.authState.take(3).collect { flowResults.add(it) }
        }
        launch {
            client.loginFromCache()
        }
        consumer.join()

        assertTrue(flowResults[0] is AuthState.Unauthenticated)
        assertTrue(flowResults[1] is AuthState.AuthLoading)
        assertTrue(flowResults[2] is AuthState.Unauthenticated)
    }

    @Test
    fun tokenRefresh_calls_setAuth_on_ffi_client() = runTest {
        val (client, ffiClient, authProvider) = createClient()

        client.login(ApplicationProvider.getApplicationContext())
        assertEquals(FakeCredentials.idToken, ffiClient.receivedAuthToken)

        val refreshedToken = "refreshed_token_value"
        authProvider.simulateTokenRefresh(refreshedToken)
        advanceUntilIdle()

        assertEquals(refreshedToken, ffiClient.receivedAuthToken)
    }

    @Test
    fun tokenRefresh_with_null_transitions_to_unauthenticated() = runTest {
        val (client, ffiClient, authProvider) = createClient()

        client.login(ApplicationProvider.getApplicationContext())
        assertTrue(client.authState.value is AuthState.Authenticated)

        authProvider.simulateTokenRefresh(null)
        advanceUntilIdle()

        assertNull(ffiClient.receivedAuthToken)
        assertTrue(client.authState.value is AuthState.Unauthenticated)
    }
}

private data object FakeCredentials {
    val email = "foo@example.com"
    val idToken = "fake id token"
}

private class FakeAuthProvider : AuthProvider<FakeCredentials> {
    var simulateFailure = false
    private var storedOnIdToken: ((String?) -> Unit)? = null

    override suspend fun login(context: Context, onIdToken: (String?) -> Unit): Result<FakeCredentials> {
        storedOnIdToken = onIdToken
        // Act like an actual function that will do async work.
        yield()
        if (simulateFailure) {
            return Result.failure(AssertionError())
        }
        return Result.success(FakeCredentials)
    }

    override suspend fun loginFromCache(onIdToken: (String?) -> Unit): Result<FakeCredentials> {
        storedOnIdToken = onIdToken
        // Act like an actual function that will do async work.
        yield()
        if (simulateFailure) {
            return Result.failure(AssertionError())
        }
        return Result.success(FakeCredentials)
    }

    override suspend fun logout(context: Context): Result<Void?> {
        // Act like an actual function that will do async work.
        yield()
        if (simulateFailure) {
            return Result.failure(AssertionError())
        }
        return Result.success(null)
    }

    override fun extractIdToken(authResult: FakeCredentials) = authResult.idToken

    fun simulateTokenRefresh(newToken: String?) {
        storedOnIdToken?.invoke(newToken)
    }
}