package io.github.rmac0001.foursight.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await

/**
 * Google Drive sign-in on Android with Google's AuthorizationClient (spec: Architecture, "Google uses
 * its Android OAuth client"). Only `drive.file`. Google matches the app by package name and signing
 * fingerprint, so there's no client ID or secret in the app, and no refresh token is stored: each
 * hour-long access token comes from authorize() again (developer.android.com/identity/authorization).
 */
class DriveAuthorizer(
    private val context: Context,
) {
    class NeedsConsent : Exception("Google Drive needs you to allow access in the app first.")

    private val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build()

    /** Set while an Activity is showing, so a consent screen can be started from it. */
    @Volatile
    var launcher: ((android.app.PendingIntent) -> Unit)? = null

    private var pending: CompletableDeferred<AuthorizationResult>? = null

    /** An access token without any screens; [NeedsConsent] when Roger hasn't allowed it yet. */
    suspend fun silentToken(): String {
        val result = Identity.getAuthorizationClient(context).authorize(request).await()
        if (result.hasResolution()) throw NeedsConsent()
        return result.accessToken ?: throw NeedsConsent()
    }

    /** Shows Google's consent screen when needed (from the app), then returns a token. */
    suspend fun interactiveToken(activity: Activity): String {
        val result = Identity.getAuthorizationClient(activity).authorize(request).await()
        if (!result.hasResolution()) return result.accessToken ?: throw NeedsConsent()
        val intent = result.pendingIntent ?: throw NeedsConsent()
        val waiting = CompletableDeferred<AuthorizationResult>()
        pending = waiting
        (launcher ?: throw NeedsConsent()).invoke(intent)
        return waiting.await().accessToken ?: throw NeedsConsent()
    }

    /** Called by the Activity with the consent screen's result. */
    fun onConsentResult(data: Intent?) {
        val waiting = pending ?: return
        pending = null
        try {
            waiting.complete(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
        } catch (e: Exception) {
            waiting.completeExceptionally(NeedsConsent())
        }
    }

    companion object {
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}
