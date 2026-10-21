package io.github.savvasg28.rampguard.core.runner

import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.Observation
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import io.github.savvasg28.rampguard.core.policy.Policy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Machine-readable view of the production track for scripts. Field names are a contract: add, never rename. */
@Serializable
data class Status(
    val app: String,
    val release: ReleaseStatus?,
    val lifecycle: LifecycleState?,
    val playStatus: RolloutStatus?,
    val fraction: Double?,
    val stage: StageStatus?,
    val nextStep: StageStatus?,
    val waiting: List<ReleaseStatus>,
    val playError: String? = null,
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    @Serializable
    data class ReleaseStatus(
        val name: String,
        val versionCodes: List<Long>,
        val lifecycle: LifecycleState,
    )

    @Serializable
    data class StageStatus(
        val index: Int,
        val percent: Int,
    )

    companion object {
        private val json =
            Json {
                prettyPrint = true
                encodeDefaults = true
                explicitNulls = true
            }

        /** What the next scheduled run would do, derived exactly as the engine does. */
        fun of(
            policy: Policy,
            obs: Observation,
        ): Status {
            val active = obs.activeRollout ?: obs.latestCompleted
            val fraction = active?.userFraction
            val current = fraction?.let { policy.stageAtOrBelow(it) }
            val next =
                current?.let { it + 1 }?.takeIf {
                    active?.status == RolloutStatus.IN_PROGRESS &&
                        it <= policy.lastStageIndex
                }
            return Status(
                app = policy.app.packageName,
                release = active?.let { ReleaseStatus(it.release.name, it.release.versionCodes, it.lifecycle) },
                lifecycle = active?.lifecycle,
                playStatus = active?.status,
                fraction = fraction,
                stage = current?.takeIf { it >= 0 }?.let { StageStatus(it, policy.stages[it]) },
                nextStep = next?.let { StageStatus(it, policy.stages[it]) },
                waiting =
                    obs.production
                        .filter {
                            it.lifecycle == LifecycleState.APPROVED_NOT_PUBLISHED ||
                                it.lifecycle == LifecycleState.NOT_APPROVED
                        }.map { ReleaseStatus(it.release.name, it.release.versionCodes, it.lifecycle) },
                playError = obs.playError,
            )
        }
    }
}
