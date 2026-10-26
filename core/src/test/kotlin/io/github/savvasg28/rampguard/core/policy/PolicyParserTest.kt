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
        policy.notify shouldContainExactly listOf(Destination.Slack(Setting.FromEnvironment("SLACK_WEBHOOK_URL")))
    }

    @Test
    fun `defaults apply to a minimal file and stages map fractions to the stage at or below`() {
        val policy = PolicyParser.parse(minimal).policyOrThrow()
        policy.notify shouldBe emptyList()
        policy.stageAtOrBelow(0.005) shouldBe -1
        policy.stageAtOrBelow(0.01) shouldBe 0
        policy.stageAtOrBelow(0.5) shouldBe 0
        policy.stageAtOrBelow(1.0) shouldBe 1
        policy.isOnStage(0.01) shouldBe true
        policy.isOnStage(0.02) shouldBe false
    }

    @Test
    fun `destinations are parsed in order with secrets as environment references`() {
        val yaml =
            minimal +
                """
                notify:
                  - slack: ${'$'}{SLACK_WEBHOOK_URL}
                  - webhook:
                      url: ${'$'}{HOOK_URL}
                      signWith: ${'$'}{HOOK_SECRET}
                  - webhook:
                      url: https://http-intake.logs.datadoghq.com/api/v2/logs?ddsource=rampguard
                      headers:
                        DD-API-KEY: ${'$'}{DD_API_KEY}
                        X-Source: rampguard
                """.trimIndent()
        PolicyParser.parse(yaml).policyOrThrow().notify shouldContainExactly
            listOf(
                Destination.Slack(Setting.FromEnvironment("SLACK_WEBHOOK_URL")),
                Destination.Webhook(
                    Setting.FromEnvironment("HOOK_URL"),
                    Setting.FromEnvironment("HOOK_SECRET"),
                    emptyMap(),
                ),
                Destination.Webhook(
                    Setting.Literal("https://http-intake.logs.datadoghq.com/api/v2/logs?ddsource=rampguard"),
                    signWith = null,
                    headers =
                        mapOf(
                            "DD-API-KEY" to Setting.FromEnvironment("DD_API_KEY"),
                            "X-Source" to Setting.Literal("rampguard"),
                        ),
                ),
            )
    }

    @Test
    fun `a destination must be exactly one kind with well-formed values`() {
        invalid(minimal + "notify:\n  - {}\n").single() shouldContain
            "notify[0]: must be exactly one of 'slack' or 'webhook'"
        invalid(minimal + "notify:\n  - slack: ' '\n").single() shouldBe "notify[0].slack: must not be blank"
        invalid(minimal + "notify:\n  - webhook:\n      url: https://h/\${PATH}\n").single() shouldContain
            "notify[0].webhook.url: a secret is written as \${NAME} for the whole value"
        invalid(
            minimal + "notify:\n  - webhook:\n      url: U\n      headers:\n        'bad header': x\n",
        ).single() shouldContain
            "'bad header' is not a valid header name"
    }

    @Test
    fun `the old map form of notify is rejected with the new shape`() {
        invalid(minimal + "notify:\n  slackWebhookEnv: SLACK_WEBHOOK_URL\n").single() shouldContain
            "notify is a list of destinations"
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
