package io.github.savvasg28.rampguard.adapters.play.publisher

import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import kotlinx.serialization.Serializable

// Wire types of the Android Publisher API v3 used for reading (ADR 0009). Writes never go through these types:
// they edit the raw track JSON so fields Rampguard does not model (release notes, targeting) survive the round trip.

@Serializable
internal data class ReleaseListDto(
    val releases: List<ReleaseSummaryDto> = emptyList(),
)

@Serializable
internal data class ReleaseSummaryDto(
    val releaseName: String? = null,
    val track: String? = null,
    val releaseLifecycleState: String? = null,
    val activeArtifacts: List<ArtifactDto> = emptyList(),
) {
    val versionCodes: Set<Long> get() = activeArtifacts.mapNotNull { it.versionCode }.toSet()
}

/** `versionCode` is a JSON number here, unlike `TrackRelease.versionCodes`, which are strings. */
@Serializable
internal data class ArtifactDto(
    val versionCode: Long? = null,
)

@Serializable
internal data class EditDto(
    val id: String? = null,
)

@Serializable
internal data class TrackDto(
    val track: String? = null,
    val releases: List<TrackReleaseDto> = emptyList(),
)

@Serializable
internal data class TrackReleaseDto(
    val name: String? = null,
    val status: String? = null,
    val userFraction: Double? = null,
    val versionCodes: List<String> = emptyList(),
) {
    val codes: Set<Long> get() = versionCodes.mapNotNull { it.toLongOrNull() }.toSet()
}

internal object PlayEnums {
    private const val LIFECYCLE_PREFIX = "RELEASE_LIFECYCLE_STATE_"

    /** Strips the wire prefix; anything unknown is `UNSPECIFIED`, which the engine ignores. */
    fun lifecycle(wire: String?): LifecycleState {
        val name = wire?.removePrefix(LIFECYCLE_PREFIX) ?: return LifecycleState.UNSPECIFIED
        return LifecycleState.entries.firstOrNull { it.name == name } ?: LifecycleState.UNSPECIFIED
    }

    fun status(wire: String?): RolloutStatus =
        when (wire) {
            "draft" -> RolloutStatus.DRAFT
            "inProgress" -> RolloutStatus.IN_PROGRESS
            "halted" -> RolloutStatus.HALTED
            "completed" -> RolloutStatus.COMPLETED
            else -> RolloutStatus.UNSPECIFIED
        }
}

/**
 * One snapshot per release, matched by version-code set: status and fraction from the track, lifecycle from the
 * release list. A release only one side knows about still appears, with the other side's fields unknown.
 */
internal fun mergeReleases(
    summaries: List<ReleaseSummaryDto>,
    track: TrackDto,
): List<ReleaseSnapshot> {
    val byCodes = summaries.filter { it.versionCodes.isNotEmpty() }.associateBy { it.versionCodes }
    val fromTrack =
        track.releases.filter { it.codes.isNotEmpty() }.map { release ->
            val summary = byCodes[release.codes]
            ReleaseSnapshot(
                release = ReleaseId(release.codes.sorted(), release.name ?: summary?.releaseName.orEmpty()),
                lifecycle = PlayEnums.lifecycle(summary?.releaseLifecycleState),
                status = PlayEnums.status(release.status),
                userFraction = release.userFraction,
            )
        }
    val trackCodes = track.releases.map { it.codes }.toSet()
    val onlyInList =
        byCodes.values.filter { it.versionCodes !in trackCodes }.map { summary ->
            ReleaseSnapshot(
                release = ReleaseId(summary.versionCodes.sorted(), summary.releaseName.orEmpty()),
                lifecycle = PlayEnums.lifecycle(summary.releaseLifecycleState),
                status = RolloutStatus.UNSPECIFIED,
                userFraction = null,
            )
        }
    return fromTrack + onlyInList
}
