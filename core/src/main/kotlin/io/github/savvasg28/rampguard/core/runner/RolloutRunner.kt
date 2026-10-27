package io.github.savvasg28.rampguard.core.runner

import io.github.savvasg28.rampguard.core.engine.Engine
import io.github.savvasg28.rampguard.core.engine.Reasons
import io.github.savvasg28.rampguard.core.engine.Verdict
import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.HaltRequest
import io.github.savvasg28.rampguard.core.model.Observation
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.policy.Policy
import io.github.savvasg28.rampguard.core.ports.AuditLog
import io.github.savvasg28.rampguard.core.ports.Clock
import io.github.savvasg28.rampguard.core.ports.PlayGateway
import kotlinx.coroutines.withTimeout
import java.time.Duration
import kotlin.time.toKotlinDuration

/** Result of one run, for the CLI and tests. */
data class RunOutcome(
    val record: DecisionRecord,
) {
    val decision: Decision get() = record.decision
}

/**
 * Orchestrates one run: observe the production track, decide, apply (unless a dry run), record. Nothing is persisted
 * between runs. Everything that touches the outside world goes through a port and carries a timeout. What happened is
 * in the [DecisionRecord]; the caller renders it for people and for the workflow.
 */
class RolloutRunner(
    private val policy: Policy,
    gateway: PlayGateway,
    private val audit: AuditLog,
    private val clock: Clock,
    private val dryRun: Boolean = false,
    private val callTimeout: Duration = Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS),
    private val log: (String) -> Unit = {},
) {
    private val gateway: PlayGateway = GuardedPlayGateway(gateway, dryRun)

    suspend fun tick(): RunOutcome {
        val observation = observe()
        return complete("tick", observation, Engine.decide(policy, observation))
    }

    suspend fun halt(request: HaltRequest): RunOutcome {
        val observation = observe()
        return complete("halt", observation, Engine.decideHalt(policy, observation, request.sanitised()))
    }

    /** Read-only: no write, no audit record. */
    suspend fun status(): Status = Status.of(policy, observe())

    private suspend fun observe(): Observation =
        try {
            Observation(production = timed { gateway.productionReleases(policy.app) })
        } catch (e: Exception) {
            Observation.playUnreadable(describe(e))
        }

    private suspend fun complete(
        command: String,
        observation: Observation,
        verdict: Verdict,
    ): RunOutcome {
        val active = observation.activeRollout
        val events = verdict.events.toMutableList()
        val applied = apply(verdict, active, events)
        val record =
            DecisionRecord(
                at = clock.now(),
                app = policy.app,
                dryRun = dryRun,
                command = command,
                release = active?.release,
                observedFraction = active?.userFraction,
                stageIndex = verdict.stageIndex,
                decision = verdict.decision,
                notes = verdict.notes,
                events = events,
                applied = applied,
            )
        timed { audit.record(record) }
        return RunOutcome(record)
    }

    /** `null` when nothing had to be written; otherwise whether the write to Play succeeded. */
    private suspend fun apply(
        verdict: Verdict,
        active: ReleaseSnapshot?,
        events: MutableList<RolloutEvent>,
    ): Boolean? {
        val decision = verdict.decision
        if (!decision.isWrite) return null
        if (dryRun) {
            log("WOULD $decision (dry run)")
            return false
        }
        val release = checkNotNull(active?.release) { "a write decision always has an active release" }
        return try {
            timed {
                when (decision) {
                    is Decision.Advance -> gateway.setRollout(policy.app, release, decision.toFraction)
                    is Decision.Complete -> gateway.complete(policy.app, release)
                    is Decision.Halt -> gateway.halt(policy.app, release)
                    is Decision.NoOp -> Unit
                }
            }
            true
        } catch (e: Exception) {
            val message = Reasons.writeFailed(describe(e))
            log(message)
            events += RolloutEvent.GuardError(message)
            false
        }
    }

    private suspend fun <T> timed(block: suspend () -> T): T = withTimeout(callTimeout.toKotlinDuration()) { block() }

    private fun describe(e: Exception): String = e.message ?: e::class.simpleName ?: "unknown error"

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS: Long = 60
    }
}
