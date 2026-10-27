package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.adapters.play.GoogleCredentialsTokenProvider
import io.github.savvasg28.rampguard.adapters.play.PlayHttp
import io.github.savvasg28.rampguard.adapters.play.publisher.PlayPublisherGateway
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.ports.AuditLog
import io.github.savvasg28.rampguard.core.ports.Clock
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
    val audit: AuditLog,
    val clock: Clock,
) {
    fun runner(
        policy: Policy,
        dryRun: Boolean,
        log: (String) -> Unit,
    ): RolloutRunner = RolloutRunner(policy, gateway, audit, clock, dryRun = dryRun, log = log)
}

fun interface WiringFactory {
    fun create(
        policy: Policy,
        out: (String) -> Unit,
    ): Wiring
}

/**
 * Production wiring. Play credentials come from Application Default Credentials (`GOOGLE_APPLICATION_CREDENTIALS`
 * or Workload Identity Federation). The record goes to stdout and, inside GitHub Actions, to the job summary and the
 * step outputs.
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
        return Wiring(
            gateway = PlayPublisherGateway(http),
            audit =
                CompositeAuditLog(
                    listOf(
                        StdoutAuditLog(out),
                        JobSummaryAuditLog(env["GITHUB_STEP_SUMMARY"]?.let(Path::of)),
                        StepOutputs(env["GITHUB_OUTPUT"]?.let(Path::of)),
                    ),
                ),
            clock = Clock { Instant.now() },
        )
    }
}
