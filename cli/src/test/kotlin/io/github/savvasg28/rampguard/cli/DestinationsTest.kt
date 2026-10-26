package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.adapters.slack.SlackNotifier
import io.github.savvasg28.rampguard.adapters.webhook.WebhookNotifier
import io.github.savvasg28.rampguard.core.policy.Destination
import io.github.savvasg28.rampguard.core.policy.Setting
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.MutableClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

class DestinationsTest {
    private val clock = MutableClock(Fixtures.monday9)
    private val missing = mutableListOf<String>()

    private fun build(
        destination: Destination,
        env: Map<String, String>,
    ) = Destinations.notifier(destination, "com.example.app", null, clock, env, missing::add)

    @Test
    fun `each destination kind builds its notifier once every reference resolves`() {
        val env = mapOf("SLACK" to "https://hooks.slack.com/x", "URL" to "https://relay.example", "KEY" to "k")

        build(Destination.Slack(Setting.FromEnvironment("SLACK")), env).shouldBeInstanceOf<SlackNotifier>()
        build(
            Destination.Webhook(
                url = Setting.FromEnvironment("URL"),
                signWith = null,
                headers =
                    mapOf(
                        "DD-API-KEY" to Setting.FromEnvironment("KEY"),
                        "X-Source" to Setting.Literal("rampguard"),
                    ),
            ),
            env,
        ).shouldBeInstanceOf<WebhookNotifier>()
        missing shouldContainExactly emptyList()
    }

    @Test
    fun `a destination with an unset variable is skipped and the variable is named`() {
        build(Destination.Slack(Setting.FromEnvironment("SLACK")), emptyMap()).shouldBeNull()
        build(
            Destination.Webhook(
                Setting.Literal("https://relay.example"),
                Setting.FromEnvironment("SECRET"),
                emptyMap(),
            ),
            emptyMap(),
        ).shouldBeNull()

        missing shouldContainExactly listOf("SLACK", "SECRET")
    }
}
