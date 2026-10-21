package io.github.savvasg28.rampguard.adapters.play

import com.google.auth.oauth2.GoogleCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Access tokens from Google Application Default Credentials: a service-account key file named by
 * `GOOGLE_APPLICATION_CREDENTIALS`, or a Workload Identity Federation config written by `google-github-actions/auth`,
 * so the GitHub Action can run without a long-lived key. Refreshes only when the token is close to expiry.
 */
class GoogleCredentialsTokenProvider(
    credentials: GoogleCredentials,
) : AccessTokenProvider {
    private val scoped: GoogleCredentials = credentials.createScoped(SCOPES)
    private val lock = Mutex()

    override suspend fun token(): String =
        lock.withLock {
            withContext(Dispatchers.IO) {
                scoped.refreshIfExpired()
                checkNotNull(scoped.accessToken) { "Google credentials returned no access token" }.tokenValue
            }
        }

    companion object {
        val SCOPES: List<String> =
            listOf(
                "https://www.googleapis.com/auth/androidpublisher",
                "https://www.googleapis.com/auth/playdeveloperreporting",
            )

        fun applicationDefault(): GoogleCredentialsTokenProvider =
            GoogleCredentialsTokenProvider(GoogleCredentials.getApplicationDefault())
    }
}
