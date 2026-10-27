package io.github.savvasg28.rampguard.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path

class GitHubActionsTest {
    private val actions =
        mapOf(
            "GITHUB_SERVER_URL" to "https://github.com",
            "GITHUB_REPOSITORY" to "acme/app",
            "GITHUB_RUN_ID" to "7",
            "GITHUB_STEP_SUMMARY" to "/tmp/summary.md",
        )

    @Test
    fun `inside Actions the run URL and summary file are derived from the environment`() {
        GitHubActions.runUrl(actions) shouldBe "https://github.com/acme/app/actions/runs/7"
        GitHubActions.summaryFile(actions) shouldBe Path.of("/tmp/summary.md")
    }

    @Test
    fun `outside Actions both are null`() {
        GitHubActions.runUrl(emptyMap()) shouldBe null
        GitHubActions.runUrl(actions - "GITHUB_RUN_ID") shouldBe null
        GitHubActions.summaryFile(emptyMap()) shouldBe null
    }
}
