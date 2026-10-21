package io.github.savvasg28.rampguard.core.testing

import io.github.savvasg28.rampguard.core.model.AppId
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.policy.NotifyConfig
import io.github.savvasg28.rampguard.core.policy.Policy
import java.time.Instant

/** Shared fixtures: the README's example policy and a few releases. */
object Fixtures {
    val app = AppId("com.example.app")

    /** Monday 2026-03-02 09:00 UTC. */
    val monday9: Instant = Instant.parse("2026-03-02T09:00:00Z")

    fun release(
        versionCode: Long,
        name: String = "v$versionCode",
    ): ReleaseId = ReleaseId(listOf(versionCode), name)

    fun policy(stages: List<Int> = listOf(1, 5, 20, 50, 100)): Policy =
        Policy(app = app, stages = stages, notify = NotifyConfig(slackWebhookEnv = null))

    val exampleYaml: String =
        """
        app: com.example.app
        stages: [1, 5, 20, 50, 100]    # percent; first stage is set at submission
        notify:
          slackWebhookEnv: SLACK_WEBHOOK_URL
        """.trimIndent()
}
