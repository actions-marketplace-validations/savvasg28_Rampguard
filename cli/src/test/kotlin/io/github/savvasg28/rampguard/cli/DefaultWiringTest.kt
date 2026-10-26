package io.github.savvasg28.rampguard.cli

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DefaultWiringTest {
    @Test
    fun `header values prefixed with env are read from the environment`() {
        val env = mapOf("DD_API_KEY" to "k")
        DefaultWiring.resolveHeader("env:DD_API_KEY", env) shouldBe "k"
        DefaultWiring.resolveHeader("env:MISSING", env) shouldBe ""
        DefaultWiring.resolveHeader("literal", env) shouldBe "literal"
    }

    @Test
    fun `the run URL is built from the Actions variables and absent elsewhere`() {
        DefaultWiring.runUrl(emptyMap()) shouldBe null
        DefaultWiring.runUrl(
            mapOf(
                "GITHUB_SERVER_URL" to "https://github.com",
                "GITHUB_REPOSITORY" to "acme/app",
                "GITHUB_RUN_ID" to "7",
            ),
        ) shouldBe "https://github.com/acme/app/actions/runs/7"
    }
}
