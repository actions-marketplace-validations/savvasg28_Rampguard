package io.github.savvasg28.rampguard.core.model

import java.time.Instant

/**
 * The audit record for one run: a tick or a command.
 *
 * @property stageIndex the configured stage the observed fraction sits at (or just above), or `null` without a rollout
 * @property applied `null` when the decision needed no write; otherwise whether the write to Play happened
 */
data class DecisionRecord(
    val at: Instant,
    val app: AppId,
    val dryRun: Boolean,
    val command: String,
    val release: ReleaseId?,
    val observedFraction: Double?,
    val stageIndex: Int?,
    val decision: Decision,
    val notes: List<String>,
    val events: List<RolloutEvent>,
    val applied: Boolean?,
)
