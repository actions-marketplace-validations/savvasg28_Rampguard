package io.github.savvasg28.rampguard.core.ports

import io.github.savvasg28.rampguard.core.model.AppId
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import java.time.Instant

/** Ports. Core depends only on these interfaces; adapters implement them. The runner wraps every call in a timeout. */
interface PlayGateway {
    /** All releases on the production track, with lifecycle, status and fraction merged. */
    suspend fun productionReleases(app: AppId): List<ReleaseSnapshot>

    /** Set the staged rollout fraction (0 < fraction < 1) of an in-progress release. */
    suspend fun setRollout(
        app: AppId,
        release: ReleaseId,
        fraction: Double,
    )

    suspend fun halt(
        app: AppId,
        release: ReleaseId,
    )

    suspend fun complete(
        app: AppId,
        release: ReleaseId,
    )
}

interface AuditLog {
    suspend fun record(record: DecisionRecord)
}

fun interface Clock {
    fun now(): Instant
}
