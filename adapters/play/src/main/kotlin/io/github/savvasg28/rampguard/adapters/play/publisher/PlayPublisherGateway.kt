package io.github.savvasg28.rampguard.adapters.play.publisher

import io.github.savvasg28.rampguard.adapters.play.MalformedResponseException
import io.github.savvasg28.rampguard.adapters.play.PlayApiException
import io.github.savvasg28.rampguard.adapters.play.PlayEndpoints
import io.github.savvasg28.rampguard.adapters.play.PlayHttp
import io.github.savvasg28.rampguard.adapters.play.PlayTransportException
import io.github.savvasg28.rampguard.adapters.play.RetryPolicy
import io.github.savvasg28.rampguard.adapters.play.decodeBody
import io.github.savvasg28.rampguard.adapters.play.retryOnTooManyRequests
import io.github.savvasg28.rampguard.core.model.AppId
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.ports.PlayGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.time.Duration

/** Another edit was committed while ours was open, and re-reading did not settle it within the attempt budget. */
class EditConflictException(
    message: String,
    cause: Throwable?,
) : IOException(message, cause)

/**
 * The commit request did not complete (timeout, connection loss), so Play may or may not have applied it. Never
 * retried: the next run re-reads the track and decides again.
 */
class WriteOutcomeUnknownException(
    message: String,
    cause: Throwable,
) : IOException(message, cause)

/**
 * The production track through the Android Publisher API (ADR 0009).
 *
 * Reads merge `tracks.releases.list` (lifecycle) with `edits.tracks.get` inside a throwaway edit (status, fraction);
 * the edit is always deleted. Writes are `edits.insert` → `edits.tracks.get` → `edits.tracks.update` →
 * `edits.commit`, editing only `status`/`userFraction` of the target release in the raw track JSON. On an edit
 * conflict the adapter opens a new edit and re-reads before writing again; if the re-read shows the write already in
 * place, it stops there, so a concurrent commit never causes a double advance.
 *
 * This class performs writes whenever asked. The dry-run check lives in the runner and in `GuardedPlayGateway`,
 * which wraps it.
 */
class PlayPublisherGateway(
    private val http: PlayHttp,
    private val endpoints: PlayEndpoints = PlayEndpoints(),
    private val retry: RetryPolicy = RetryPolicy(),
    private val maxWriteAttempts: Int = DEFAULT_WRITE_ATTEMPTS,
    private val conflictBackoff: Duration = Duration.ofSeconds(2),
) : PlayGateway {
    init {
        require(maxWriteAttempts >= 1) { "maxWriteAttempts must be at least 1" }
    }

    override suspend fun productionReleases(app: AppId): List<ReleaseSnapshot> {
        val list =
            decodeBody<ReleaseListDto>(
                retry.retryOnTooManyRequests { http.get("${appUrl(app)}/tracks/$TRACK/releases") },
                "tracks.releases.list",
            )
        val track = withEdit(app) { editId -> readTrack(app, editId) }
        return mergeReleases(list.releases, decodeBody<TrackDto>(track, "edits.tracks.get"))
    }

    override suspend fun setRollout(
        app: AppId,
        release: ReleaseId,
        fraction: Double,
    ) = write(app, release, TrackEdit.SetFraction(fraction))

    override suspend fun halt(
        app: AppId,
        release: ReleaseId,
    ) = write(app, release, TrackEdit.Halt)

    override suspend fun complete(
        app: AppId,
        release: ReleaseId,
    ) = write(app, release, TrackEdit.Complete)

    private suspend fun write(
        app: AppId,
        release: ReleaseId,
        edit: TrackEdit,
    ) {
        var lastConflict: PlayApiException? = null
        repeat(maxWriteAttempts) { attempt ->
            if (attempt > 0) delay(conflictBackoff.multipliedBy(attempt.toLong()).toMillis())
            val editId = openEdit(app)
            var committed = false
            try {
                val track = readTrack(app, editId)
                val current = requireRelease(track, release, edit)
                if (edit.alreadyApplied(current)) return
                val updated = replaceRelease(track, release, edit.apply(current))
                http.put(editUrl(app, editId, "/tracks/$TRACK"), updated)
                commit(app, editId, edit)
                committed = true
                return
            } catch (e: PlayApiException) {
                if (!e.isEditConflict()) throw e
                lastConflict = e
            } finally {
                if (!committed) discardEdit(app, editId)
            }
        }
        throw EditConflictException(
            "could not ${edit.description} on $release: edit conflict persisted after $maxWriteAttempts attempts",
            lastConflict,
        )
    }

    private fun requireRelease(
        track: JsonObject,
        release: ReleaseId,
        edit: TrackEdit,
    ): JsonObject =
        findRelease(track, release)
            ?: throw UnexpectedReleaseStateException("cannot ${edit.description}: $release is not on $TRACK")

    private suspend fun commit(
        app: AppId,
        editId: String,
        edit: TrackEdit,
    ) {
        try {
            http.post("${editUrl(app, editId, "")}:commit", null)
        } catch (e: PlayTransportException) {
            throw WriteOutcomeUnknownException(
                "commit to ${edit.description} did not complete (${e.message}); not retried, the next tick re-reads",
                e,
            )
        }
    }

    private suspend fun <T> withEdit(
        app: AppId,
        block: suspend (String) -> T,
    ): T {
        val editId = openEdit(app)
        try {
            return block(editId)
        } finally {
            discardEdit(app, editId)
        }
    }

    private suspend fun openEdit(app: AppId): String {
        val body = retry.retryOnTooManyRequests { http.post("${appUrl(app)}/edits", JsonObject(emptyMap())) }
        return decodeBody<EditDto>(body, "edits.insert").id?.takeIf { it.isNotBlank() }
            ?: throw MalformedResponseException("edits.insert returned no edit id")
    }

    private suspend fun readTrack(
        app: AppId,
        editId: String,
    ): JsonObject {
        val body = retry.retryOnTooManyRequests { http.get(editUrl(app, editId, "/tracks/$TRACK")) }
        return body as? JsonObject ?: throw MalformedResponseException("edits.tracks.get did not return an object")
    }

    /** Best effort, and safe to run while cancelling: an edit left open expires on its own and changes nothing. */
    private suspend fun discardEdit(
        app: AppId,
        editId: String,
    ) {
        try {
            withContext(NonCancellable) { http.delete(editUrl(app, editId, "")) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A committed or expired edit cannot be deleted; nothing to do.
        }
    }

    private fun appUrl(app: AppId): String = "${endpoints.publisher}/applications/${app.packageName}"

    private fun editUrl(
        app: AppId,
        editId: String,
        suffix: String,
    ): String = "${appUrl(app)}/edits/$editId$suffix"

    companion object {
        const val DEFAULT_WRITE_ATTEMPTS: Int = 3
        private const val TRACK = "production"
        private const val CONFLICT = 409

        /**
         * Edit conflicts as Play reports them. 409 / `ABORTED` is the documented form; the exact response to a
         * concurrent commit is to be confirmed with a write-capable account (ADR 0009).
         */
        internal fun PlayApiException.isEditConflict(): Boolean = httpStatus == CONFLICT || apiStatus == "ABORTED"
    }
}
