package io.github.savvasg28.rampguard.core.testing

import io.github.savvasg28.rampguard.core.model.AppId
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import io.github.savvasg28.rampguard.core.ports.AuditLog
import io.github.savvasg28.rampguard.core.ports.Clock
import io.github.savvasg28.rampguard.core.ports.Notifier
import io.github.savvasg28.rampguard.core.ports.PlayGateway
import java.time.Duration
import java.time.Instant

class MutableClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    fun advanceHours(hours: Long) = advance(Duration.ofHours(hours))
}

/**
 * An in-memory production track. Writes behave like Play: advancing keeps the release in progress, halting and
 * completing change the status. The `human*` helpers model what a person does in the Play Console.
 */
class FakePlayGateway : PlayGateway {
    val production = mutableListOf<ReleaseSnapshot>()
    val writes = mutableListOf<String>()
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null

    override suspend fun productionReleases(app: AppId): List<ReleaseSnapshot> {
        readFailure?.let { throw it }
        return production.toList()
    }

    override suspend fun setRollout(
        app: AppId,
        release: ReleaseId,
        fraction: Double,
    ) {
        writeFailure?.let { throw it }
        writes += "setRollout $release $fraction"
        update(release) { it.copy(status = RolloutStatus.IN_PROGRESS, userFraction = fraction) }
    }

    override suspend fun halt(
        app: AppId,
        release: ReleaseId,
    ) {
        writeFailure?.let { throw it }
        writes += "halt $release"
        update(release) { it.copy(status = RolloutStatus.HALTED) }
    }

    override suspend fun complete(
        app: AppId,
        release: ReleaseId,
    ) {
        writeFailure?.let { throw it }
        writes += "complete $release"
        update(release) { it.copy(status = RolloutStatus.COMPLETED, userFraction = null) }
    }

    fun humanSubmit(
        release: ReleaseId,
        lifecycle: LifecycleState,
    ) {
        production += ReleaseSnapshot(release, lifecycle, RolloutStatus.DRAFT, null)
    }

    fun humanPublish(
        release: ReleaseId,
        fraction: Double,
    ) {
        production.removeAll { it.release.sameAs(release) }
        production += ReleaseSnapshot(release, LifecycleState.PUBLISHED, RolloutStatus.IN_PROGRESS, fraction)
    }

    fun humanSetFraction(
        release: ReleaseId,
        fraction: Double,
    ) = update(release) { it.copy(userFraction = fraction) }

    fun humanHalt(release: ReleaseId) = update(release) { it.copy(status = RolloutStatus.HALTED) }

    fun humanResume(release: ReleaseId) = update(release) { it.copy(status = RolloutStatus.IN_PROGRESS) }

    fun humanComplete(release: ReleaseId) =
        update(release) {
            it.copy(status = RolloutStatus.COMPLETED, userFraction = null)
        }

    fun completedRelease(release: ReleaseId) {
        production += ReleaseSnapshot(release, LifecycleState.PUBLISHED, RolloutStatus.COMPLETED, null)
    }

    private fun update(
        release: ReleaseId,
        f: (ReleaseSnapshot) -> ReleaseSnapshot,
    ) {
        val i = production.indexOfFirst { it.release.sameAs(release) }
        require(i >= 0) { "no such release on the track: $release" }
        production[i] = f(production[i])
    }
}

class RecordingNotifier : Notifier {
    val events = mutableListOf<RolloutEvent>()
    var failure: Throwable? = null

    override suspend fun notify(event: RolloutEvent) {
        failure?.let { throw it }
        events += event
    }
}

class RecordingAuditLog : AuditLog {
    val records = mutableListOf<DecisionRecord>()

    override suspend fun record(record: DecisionRecord) {
        records += record
    }
}
