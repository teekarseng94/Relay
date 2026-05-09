package com.gastropos.relay

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.tasks.await

/**
 * Single-purpose Firebase Auth gateway for the relay app.
 *
 * Why this exists:
 *   The rdp-pos Firestore Security Rules gate writes to /orders on the
 *   custom claim `app == 'gastropos-relay'` (set server-side via the Admin
 *   SDK on the user `rdp@admin.com`). For the relay's writes to be
 *   accepted, the Firestore SDK's outgoing request must carry an ID token
 *   minted from that signed-in user.
 *
 * Usage from OrderRelayClient:
 *   scope.launch {
 *     RelayAuth.ensureSignedIn()
 *     firestore.collection("orders").document(id).set(data).await()
 *   }
 *
 * `ensureSignedIn()` is idempotent and cheap on the happy path: it just
 * inspects the cached ID token. It only triggers a network sign-in when
 * the token is missing or lacks the relay claim (e.g. immediately after
 * the Admin SDK granted/revoked refresh tokens).
 */
object RelayAuth {

    private const val TAG = "RelayAuth"
    private const val RELAY_EMAIL = "rdp@admin.com"
    private const val CLAIM_KEY = "app"
    private const val CLAIM_VALUE = "gastropos-relay"

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    suspend fun ensureSignedIn() {
        val current = auth.currentUser
        if (current != null) {
            // Cheap path: no network call, returns the cached token.
            val token = current.getIdToken(false).await()
            if (token.claims[CLAIM_KEY] == CLAIM_VALUE) {
                return
            }
            Log.w(
                TAG,
                "Relay user ${current.uid} signed in but missing claim '$CLAIM_KEY=$CLAIM_VALUE'; " +
                    "forcing token refresh."
            )
            // Try to pick up a freshly-issued claim without re-prompting the password.
            try {
                val refreshed = current.getIdToken(true).await()
                if (refreshed.claims[CLAIM_KEY] == CLAIM_VALUE) {
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token refresh failed; falling back to full sign-in: ${e.message}")
            }
            // Fall through and re-authenticate from scratch.
        }

        val password = BuildConfig.RELAY_PASSWORD
        check(password.isNotEmpty()) {
            "BuildConfig.RELAY_PASSWORD is empty. Add 'RELAY_PASSWORD=<password>' to " +
                "local.properties and rebuild."
        }

        try {
            auth.signInWithEmailAndPassword(RELAY_EMAIL, password).await()
        } catch (e: FirebaseAuthInvalidUserException) {
            throw IllegalStateException(
                "Relay user '$RELAY_EMAIL' does not exist in Firebase project. " +
                    "Re-create the user in Firebase Console > Authentication > Users.",
                e
            )
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw IllegalStateException(
                "Wrong password for '$RELAY_EMAIL'. Reset it in Firebase Console > " +
                    "Authentication > Users (kebab menu > Reset password) and update " +
                    "RELAY_PASSWORD in local.properties.",
                e
            )
        }

        // Force-refresh so the freshly-minted ID token definitely contains the claim
        // (refresh tokens are revoked server-side after a custom-claim change).
        val refreshed = auth.currentUser?.getIdToken(true)?.await()
            ?: error("Sign-in succeeded but currentUser is null")

        if (refreshed.claims[CLAIM_KEY] != CLAIM_VALUE) {
            throw IllegalStateException(
                "Signed in as '$RELAY_EMAIL' but the user has no '$CLAIM_KEY=$CLAIM_VALUE' " +
                    "custom claim. Run scripts/grant-relay-claim.cjs from the rdp-pos repo " +
                    "and try again."
            )
        }
    }
}
