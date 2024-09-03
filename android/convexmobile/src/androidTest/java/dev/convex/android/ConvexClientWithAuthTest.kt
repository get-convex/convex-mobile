package dev.convex.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.convex.android.testing.FakeFfiClient
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConvexClientWithAuthTest {
    private lateinit var ffiClient: FakeFfiClient
    private lateinit var client: ConvexClientWithAuth<FakeCredentials>
    private lateinit var authProvider: FakeAuthProvider

    @Before
    fun setup() {
        ffiClient = FakeFfiClient()
        authProvider = FakeAuthProvider()
        client = ConvexClientWithAuth(
            "foo://bar",
            authProvider
        ) { ffiClient }
    }

    @Test
    fun login_success_publishes_correct_states() = runTest {
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
        authProvider.simulateFailure = true
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
        authProvider.simulateFailure = true
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
}

private data object FakeCredentials {
    val email = "foo@example.com"
    val idToken = "fake id token"
}

private class FakeAuthProvider : AuthProvider<FakeCredentials> {
    var simulateFailure = false

    override suspend fun login(context: Context): Result<FakeCredentials> {
        // Act like an actual function that will do async work.
        yield()
        if (simulateFailure) {
            return Result.failure(AssertionError())
        }
        return Result.success(FakeCredentials)
    }

    override suspend fun loginFromCache(): Result<FakeCredentials> {
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

}