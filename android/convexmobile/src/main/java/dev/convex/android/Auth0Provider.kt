package dev.convex.android

import android.content.Context
import com.auth0.android.Auth0
import com.auth0.android.authentication.AuthenticationException
import com.auth0.android.callback.Callback
import com.auth0.android.provider.WebAuthProvider
import com.auth0.android.result.Credentials
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Auth0Provider(
    clientId: String,
    domain: String,
    private val scheme: String = "app",
) : AuthProvider<Credentials> {
    private val auth0 = Auth0(clientId, domain)

    override suspend fun login(context: Context): Result<Credentials> = suspendCoroutine { cont ->
        WebAuthProvider.login(auth0).withScheme(scheme)
            .start(context, object : Callback<Credentials, AuthenticationException> {
                override fun onFailure(error: AuthenticationException) {
                    cont.resume(Result.failure(error))
                }

                override fun onSuccess(result: Credentials) {
                    cont.resume(Result.success(result))
                }
            })
    }

    override suspend fun logout(context: Context): Result<Void?> = suspendCoroutine { cont ->
        WebAuthProvider.logout(auth0).withScheme(scheme)
            .start(context, object : Callback<Void?, AuthenticationException> {
                override fun onFailure(error: AuthenticationException) {
                    cont.resume(Result.failure(error))
                }

                override fun onSuccess(result: Void?) {
                    cont.resume(Result.success(result))
                }
            })
    }

    override fun extractIdToken(authResult: Credentials): String = authResult.idToken

}