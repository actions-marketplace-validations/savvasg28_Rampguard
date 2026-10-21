package io.github.savvasg28.rampguard.core.model

import kotlinx.serialization.Serializable

/** A Google Play application, identified by its package name. */
@JvmInline
@Serializable
value class AppId(
    val packageName: String,
) {
    override fun toString(): String = packageName
}

/**
 * A production release as Play identifies it: the set of version codes it ships plus its display name.
 * Two snapshots describe the same release when their version codes match.
 */
@Serializable
data class ReleaseId(
    val versionCodes: List<Long>,
    val name: String,
) {
    init {
        require(versionCodes.isNotEmpty()) { "a release must carry at least one versionCode" }
    }

    val maxVersionCode: Long get() = versionCodes.max()

    fun contains(versionCode: Long): Boolean = versionCode in versionCodes

    /** Identity is the set of version codes; the name is informational only. */
    fun sameAs(other: ReleaseId?): Boolean = other != null && versionCodes.toSet() == other.versionCodes.toSet()

    override fun toString(): String = "$name[${versionCodes.joinToString(",")}]"
}

/** Who performed an action on the release. */
@Serializable
enum class Actor { GUARD, HUMAN }
