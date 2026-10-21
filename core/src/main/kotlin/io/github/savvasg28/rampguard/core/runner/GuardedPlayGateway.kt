package io.github.savvasg28.rampguard.core.runner

import io.github.savvasg28.rampguard.core.model.AppId
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.ports.PlayGateway

/** Thrown when a write is attempted during a dry run. Fails closed. */
class WriteRefusedException(
    operation: String,
) : IllegalStateException("refusing to $operation: this is a dry run")

/**
 * Wraps a [PlayGateway] so that writes are impossible during a dry run.
 * The runner already skips writes on a dry run; this is the second, independent check.
 */
class GuardedPlayGateway(
    private val delegate: PlayGateway,
    private val dryRun: Boolean,
) : PlayGateway {
    override suspend fun productionReleases(app: AppId): List<ReleaseSnapshot> = delegate.productionReleases(app)

    override suspend fun setRollout(
        app: AppId,
        release: ReleaseId,
        fraction: Double,
    ) {
        guard("set rollout of $release to $fraction")
        delegate.setRollout(app, release, fraction)
    }

    override suspend fun halt(
        app: AppId,
        release: ReleaseId,
    ) {
        guard("halt $release")
        delegate.halt(app, release)
    }

    override suspend fun complete(
        app: AppId,
        release: ReleaseId,
    ) {
        guard("complete $release")
        delegate.complete(app, release)
    }

    private fun guard(operation: String) {
        if (dryRun) throw WriteRefusedException(operation)
    }
}
