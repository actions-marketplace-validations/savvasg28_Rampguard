package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.adapters.play.GoogleCredentialsTokenProvider
import io.github.savvasg28.rampguard.adapters.play.PlayHttp
import io.github.savvasg28.rampguard.adapters.play.publisher.PlayPublisherGateway
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.ports.AuditLog
import io.github.savvasg28.rampguard.core.ports.Clock
import io.github.savvasg28.rampguard.core.ports.Notifier
import io.github.savvasg28.rampguard.core.ports.PlayGateway
import io.github.savvasg28.rampguard.core.runner.RolloutRunner
import java.io.IOException
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
 * or Workload Identity Federation). [Destinations] builds each `notify` destination; one whose environment variable is
 * not set is skipped with a line on stdout. The audit goes to stdout and, inside GitHub Actions, to the job summary.
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
        val clock = Clock { Instant.now() }
        val notifiers =
            policy.notify.mapIndexedNotNull { index, destination ->
                Destinations.notifier(
                    destination,
                    policy.app.packageName,
                    GitHubActions.runUrl(env),
                    clock,
                    env,
                ) { variable ->
                    out("notify[$index] skipped: environment variable $variable is not set")
                }
            }
        return Wiring(
            gateway = PlayPublisherGateway(http),
            notifier = CompositeNotifier(listOf(StdoutNotifier(out)) + notifiers),
            audit = CompositeAuditLog(listOf(StdoutAuditLog(out), JobSummaryAuditLog(GitHubActions.summaryFile(env)))),
            clock = clock,
        )
    }
}
