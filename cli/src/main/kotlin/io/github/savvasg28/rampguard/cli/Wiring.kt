package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.adapters.play.GoogleCredentialsTokenProvider
import io.github.savvasg28.rampguard.adapters.play.PlayHttp
import io.github.savvasg28.rampguard.adapters.play.publisher.PlayPublisherGateway
import io.github.savvasg28.rampguard.adapters.slack.SlackNotifier
import io.github.savvasg28.rampguard.adapters.webhook.WebhookNotifier
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.ports.AuditLog
import io.github.savvasg28.rampguard.core.ports.Clock
import io.github.savvasg28.rampguard.core.ports.Notifier
import io.github.savvasg28.rampguard.core.ports.PlayGateway
import io.github.savvasg28.rampguard.core.runner.RolloutRunner
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

/** No Google credentials could be found. The message says how to provide them; the cause is not printed. */
class CredentialsMissingException(
    cause: Throwable,
) : IllegalStateException(
        "no Google credentials found: set GOOGLE_APPLICATION_CREDENTIALS to the service-account key file, " +
            "or authenticate with google-github-actions/auth in the workflow",
        cause,
    )

/** The adapters a command runs with. Tests supply fakes; [DefaultWiring] reads the real environment. */
data class Wiring(
    val gateway: PlayGateway,
    val notifier: Notifier,
    val audit: AuditLog,
    val clock: Clock,
) {
    fun runner(
        policy: Policy,
        dryRun: Boolean,
        log: (String) -> Unit,
    ): RolloutRunner = RolloutRunner(policy, gateway, notifier, audit, clock, dryRun = dryRun, log = log)
}

fun interface WiringFactory {
    fun create(
        policy: Policy,
        out: (String) -> Unit,
    ): Wiring
}

/**
 * Production wiring. Play credentials come from Application Default Credentials (`GOOGLE_APPLICATION_CREDENTIALS`
 * or Workload Identity Federation). Webhook URLs, secrets and header values are read from the environment variables the
 * policy names and are never printed. The audit goes to stdout and, inside GitHub Actions, to the job summary.
 */
object DefaultWiring : WiringFactory {
    override fun create(
        policy: Policy,
        out: (String) -> Unit,
    ): Wiring {
        val env = System.getenv()
        val credentials =
            try {
                GoogleCredentialsTokenProvider.applicationDefault()
            } catch (e: IOException) {
                throw CredentialsMissingException(e)
            }
        val http = PlayHttp(credentials)
        val slack =
            policy.notify.slackWebhookEnv
                ?.let {
                    env[it]
                }?.takeIf { it.isNotBlank() }
                ?.let { SlackNotifier(it, policy.app.packageName) }
        val clock = Clock { Instant.now() }
        val webhook =
            policy.notify.webhook?.let { config ->
                env[config.urlEnv]?.takeIf { it.isNotBlank() }?.let { url ->
                    WebhookNotifier(
                        url = url,
                        app = policy.app.packageName,
                        clock = clock,
                        headers = config.headers.mapValues { (_, value) -> resolveHeader(value, env) },
                        secret = config.secretEnv?.let(env::get),
                        runUrl = runUrl(env),
                    )
                }
            }
        return Wiring(
            gateway = PlayPublisherGateway(http),
            notifier = CompositeNotifier(listOfNotNull(StdoutNotifier(out), slack, webhook)),
            audit =
                CompositeAuditLog(
                    listOf(StdoutAuditLog(out), JobSummaryAuditLog(env["GITHUB_STEP_SUMMARY"]?.let(Path::of))),
                ),
            clock = clock,
        )
    }

    /** A header value of the form `env:NAME` is read from the environment; anything else is sent as written. */
    internal fun resolveHeader(
        value: String,
        env: Map<String, String>,
    ): String = if (value.startsWith(ENV_PREFIX)) env[value.removePrefix(ENV_PREFIX)].orEmpty() else value

    /** The URL of the current GitHub Actions run, or null outside Actions. */
    internal fun runUrl(env: Map<String, String>): String? {
        val server = env["GITHUB_SERVER_URL"] ?: return null
        val repo = env["GITHUB_REPOSITORY"] ?: return null
        val run = env["GITHUB_RUN_ID"] ?: return null
        return "$server/$repo/actions/runs/$run"
    }

    private const val ENV_PREFIX = "env:"
}
