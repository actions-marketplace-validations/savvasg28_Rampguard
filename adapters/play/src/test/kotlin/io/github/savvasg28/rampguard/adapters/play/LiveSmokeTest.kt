package io.github.savvasg28.rampguard.adapters.play

import io.github.savvasg28.rampguard.adapters.play.publisher.PlayPublisherGateway
import io.github.savvasg28.rampguard.core.model.AppId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Manual, read-only check against a real app. Never runs in CI: it needs `RAMPGUARD_LIVE_PACKAGE=<package>` and
 * Google credentials (`GOOGLE_APPLICATION_CREDENTIALS`):
 *
 *     RAMPGUARD_LIVE_PACKAGE=com.example.app ./gradlew :adapters:play:test --tests '*LiveSmokeTest*' -i
 *
 * It opens and deletes an edit to read the track, and never writes.
 */
@EnabledIfEnvironmentVariable(named = "RAMPGUARD_LIVE_PACKAGE", matches = ".+")
class LiveSmokeTest {
    @Test
    fun `prints the production track`() =
        runBlocking<Unit> {
            val app = AppId(System.getenv("RAMPGUARD_LIVE_PACKAGE"))
            val http = PlayHttp(GoogleCredentialsTokenProvider.applicationDefault())
            PlayPublisherGateway(http).productionReleases(app).forEach {
                println(
                    "release ${it.release} lifecycle=${it.lifecycle} status=${it.status} fraction=${it.userFraction}",
                )
            }
        }
}
