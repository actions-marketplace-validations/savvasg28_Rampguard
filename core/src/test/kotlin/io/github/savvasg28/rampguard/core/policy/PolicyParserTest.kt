package io.github.savvasg28.rampguard.core.policy

import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class PolicyParserTest {
    private val minimal = "app: com.example.app\nstages: [1, 100]\n"

    private fun invalid(yaml: String): List<String> =
        PolicyParser
            .parse(yaml)
            .shouldBeInstanceOf<PolicyResult.Invalid>()
            .errors
            .map { it.toString() }

    @Test
    fun `the README example parses into the expected policy`() {
        val policy = PolicyParser.parse(Fixtures.exampleYaml).shouldBeInstanceOf<PolicyResult.Valid>().policy
        policy.app.packageName shouldBe "com.example.app"
        policy.stages shouldContainExactly listOf(1, 5, 20, 50, 100)
        policy.notify.slackWebhookEnv shouldBe "SLACK_WEBHOOK_URL"
    }

    @Test
    fun `defaults apply to a minimal file and stages map fractions to the stage at or below`() {
        val policy = PolicyParser.parse(minimal).policyOrThrow()
        policy.notify.slackWebhookEnv shouldBe null
        policy.stageAtOrBelow(0.005) shouldBe -1
        policy.stageAtOrBelow(0.01) shouldBe 0
        policy.stageAtOrBelow(0.5) shouldBe 0
        policy.stageAtOrBelow(1.0) shouldBe 1
        policy.isOnStage(0.01) shouldBe true
        policy.isOnStage(0.02) shouldBe false
    }

    @Test
    fun `a webhook block is parsed with its headers and validated`() {
        val yaml =
            minimal +
                "notify:\n  webhook:\n    urlEnv: HOOK_URL\n    secretEnv: HOOK_SECRET\n" +
                "    headers:\n      DD-API-KEY: env:DD_API_KEY\n      X-Source: rampguard\n"
        val webhook =
            PolicyParser
                .parse(yaml)
                .policyOrThrow()
                .notify.webhook!!
        webhook.urlEnv shouldBe "HOOK_URL"
        webhook.secretEnv shouldBe "HOOK_SECRET"
        webhook.headers shouldBe mapOf("DD-API-KEY" to "env:DD_API_KEY", "X-Source" to "rampguard")

        invalid(minimal + "notify:\n  webhook:\n    urlEnv: ' '\n").single() shouldContain "notify.webhook.urlEnv"
        invalid(
            minimal + "notify:\n  webhook:\n    urlEnv: U\n    headers:\n      'bad header': x\n",
        ).single() shouldContain
            "'bad header' is not a valid header name"
    }

    @Test
    fun `unknown keys are rejected`() {
        val errors = invalid(minimal + "mod: enforce\n")
        errors shouldHaveSize 1
        errors.single() shouldContain "mod"
    }

    @Test
    fun `retired keys are rejected with a pointer to the decisions`() {
        invalid(minimal + "minSoakHours: 24\n").single() shouldContain "'minSoakHours' was removed"
        invalid(minimal + "blackout:\n  timezone: UTC\n").single() shouldContain "ADR 0010 to 0012"
        invalid(minimal + "mode: observe\n").single() shouldContain "--dry-run"
        invalid(minimal + "gates: []\n").single() shouldContain "'gates' was removed"
    }

    @Test
    fun `stages must be strictly increasing, within range and end at 100`() {
        invalid("app: a\nstages: [1, 20, 5, 100]") shouldContainExactly
            listOf("stages: must be strictly increasing; was [1, 20, 5, 100]")
        invalid("app: a\nstages: [1, 50]") shouldContainExactly
            listOf("stages: the last stage must be 100 (the rollout is completed, no 99.9% trick)")
        invalid("app: a\nstages: [0, 100]") shouldContainExactly
            listOf("stages: every stage must be within 1..100 percent")
        invalid("app: a\nstages: [100]") shouldContainExactly
            listOf("stages: need at least two stages (the submitted one and 100)")
    }

    @Test
    fun `every problem is reported with its path`() {
        invalid("app: \" \"\nstages: [1, 50]\n") shouldContainExactly
            listOf(
                "app: must be the application package name",
                "stages: the last stage must be 100 (the rollout is completed, no 99.9% trick)",
            )
    }

    @Test
    fun `invalid YAML and wrong types are reported as errors not exceptions`() {
        invalid("app: [").single() shouldContain "app"
        invalid("app: a\nstages: nope").single() shouldContain "stages"
        invalid("stages: [1, 100]").single() shouldContain "app"
    }

    @Test
    fun `policyOrThrow renders every error`() {
        val e = shouldThrow<InvalidPolicyException> { PolicyParser.parse("app: a\nstages: [1, 50]").policyOrThrow() }
        e.message shouldContain "the last stage must be 100"
        e.result.errors shouldHaveSize 1
    }
}
