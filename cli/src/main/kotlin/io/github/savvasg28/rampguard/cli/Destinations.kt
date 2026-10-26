package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.adapters.slack.SlackNotifier
import io.github.savvasg28.rampguard.adapters.webhook.WebhookNotifier
import io.github.savvasg28.rampguard.adapters.webhook.WebhookTarget
import io.github.savvasg28.rampguard.core.policy.Destination
import io.github.savvasg28.rampguard.core.policy.Setting
import io.github.savvasg28.rampguard.core.ports.Clock
import io.github.savvasg28.rampguard.core.ports.Notifier

/** Turns a configured [Destination] into a [Notifier], resolving its environment references against the environment. */
object Destinations {
    /**
     * Returns null, after calling [onMissing] with the variable name, when a setting names an environment variable that
     * is not set. Resolved values are passed straight to the adapter and never printed.
     */
    fun notifier(
        destination: Destination,
        app: String,
        runUrl: String?,
        clock: Clock,
        env: Map<String, String>,
        onMissing: (String) -> Unit,
    ): Notifier? {
        val missing =
            destination.settings().filterIsInstance<Setting.FromEnvironment>().firstOrNull {
                it.variable !in
                    env
            }
        if (missing != null) {
            onMissing(missing.variable)
            return null
        }

        fun Setting.value(): String =
            when (this) {
                is Setting.Literal -> value
                is Setting.FromEnvironment -> env.getValue(variable)
            }
        return when (destination) {
            is Destination.Slack -> SlackNotifier(destination.webhook.value(), app)
            is Destination.Webhook ->
                WebhookNotifier(
                    WebhookTarget(
                        url = destination.url.value(),
                        headers = destination.headers.mapValues { (_, setting) -> setting.value() },
                        secret = destination.signWith?.value(),
                    ),
                    app,
                    runUrl,
                    clock,
                )
        }
    }

    private fun Destination.settings(): List<Setting> =
        when (this) {
            is Destination.Slack -> listOf(webhook)
            is Destination.Webhook -> listOfNotNull(url, signWith) + headers.values
        }
}
