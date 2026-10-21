package io.github.savvasg28.rampguard.adapters.play.publisher

import io.github.savvasg28.rampguard.adapters.play.FakePlay
import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.fixture
import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.json
import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.noContent
import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.ok
import io.github.savvasg28.rampguard.adapters.play.MalformedResponseException
import io.github.savvasg28.rampguard.adapters.play.PlayApiException
import io.github.savvasg28.rampguard.adapters.play.RetryPolicy
import io.github.savvasg28.rampguard.core.model.AppId
import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.ReleaseId
import io.github.savvasg28.rampguard.core.model.ReleaseSnapshot
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration

class PlayPublisherGatewayTest {
    private val play = FakePlay()
    private val app = AppId("com.example.app")
    private val v100 = ReleaseId(listOf(100), "2.3.0")
    private val v101 = ReleaseId(listOf(101), "2.4.0")
    private val fastRetry =
        RetryPolicy(maxAttempts = 3, initialDelay = Duration.ofMillis(1), maxDelay = Duration.ofMillis(5))

    @AfterEach
    fun stop() = play.close()

    private fun gateway(timeout: Duration = Duration.ofSeconds(5)) =
        PlayPublisherGateway(play.http(timeout), play.endpoints, fastRetry, conflictBackoff = Duration.ofMillis(1))

    /** Routes for one edit: insert, track read (in order), update, commit and delete. */
    private fun editRoutes(
        vararg tracks: String,
        commit: Array<okhttp3.mockwebserver.MockResponse> = arrayOf(ok(fixture("publisher/edits-insert.json"))),
    ) {
        play.on("POST", "/edits", ok(fixture("publisher/edits-insert.json")))
        play.on("GET", "/edits/EDIT_ID/tracks/production", *tracks.map(::ok).toTypedArray())
        play.on("PUT", "/edits/EDIT_ID/tracks/production", ok(fixture("publisher/track-production.json")))
        play.on("POST", "/edits/EDIT_ID:commit", *commit)
        play.on("DELETE", "/edits/EDIT_ID", noContent())
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.releases(): List<JsonObject> = getValue("releases").jsonArray.map { it.jsonObject }

    private fun withField(
        release: JsonObject,
        key: String,
        value: JsonElement,
    ): JsonObject = JsonObject(release + (key to value))

    // ---- reads

    @Test
    fun `production releases merge lifecycle from the list with status and fraction from the track`() =
        runTest {
            play.on("GET", "/tracks/production/releases", ok(fixture("publisher/releases-list.json")))
            editRoutes(fixture("publisher/track-production.json"))

            gateway().productionReleases(app) shouldBe
                listOf(
                    ReleaseSnapshot(v101, LifecycleState.PUBLISHED, RolloutStatus.IN_PROGRESS, 0.1),
                    ReleaseSnapshot(v100, LifecycleState.PUBLISHED, RolloutStatus.COMPLETED, null),
                )
        }

    @Test
    fun `reading opens an edit, never writes, and always deletes the edit`() =
        runTest {
            play.on("GET", "/tracks/production/releases", ok(fixture("publisher/releases-list.json")))
            editRoutes(fixture("publisher/track-production.json"))

            gateway().productionReleases(app)

            play.calls.map { "${it.method} ${it.path.substringAfter("/applications/com.example.app")}" } shouldBe
                listOf(
                    "GET /tracks/production/releases",
                    "POST /edits",
                    "GET /edits/EDIT_ID/tracks/production",
                    "DELETE /edits/EDIT_ID",
                )
        }

    @Test
    fun `the edit is deleted even when reading the track fails`() =
        runTest {
            play.on("GET", "/tracks/production/releases", ok(fixture("publisher/releases-list.json")))
            play.on("POST", "/edits", ok(fixture("publisher/edits-insert.json")))
            play.on("GET", "/edits/EDIT_ID/tracks/production", json(503, """{"error":{"code":503}}"""))
            play.on("DELETE", "/edits/EDIT_ID", noContent())

            shouldThrow<PlayApiException> { gateway().productionReleases(app) }.httpStatus shouldBe 503
            play.count("DELETE", "/edits/EDIT_ID") shouldBe 1
        }

    @Test
    fun `unknown lifecycle states are UNSPECIFIED and releases only in the list still appear`() =
        runTest {
            val list =
                """{"releases":[
                    {"activeArtifacts":[{"versionCode":102}],"releaseName":"2.5.0",
                     "releaseLifecycleState":"RELEASE_LIFECYCLE_STATE_IN_REVIEW"},
                    {"activeArtifacts":[{"versionCode":101}],"releaseName":"2.4.0",
                     "releaseLifecycleState":"RELEASE_LIFECYCLE_STATE_SOMETHING_NEW"}]}"""
            play.on("GET", "/tracks/production/releases", ok(list))
            editRoutes(fixture("publisher/track-production.json"))

            gateway().productionReleases(app) shouldBe
                listOf(
                    ReleaseSnapshot(v101, LifecycleState.UNSPECIFIED, RolloutStatus.IN_PROGRESS, 0.1),
                    ReleaseSnapshot(v100, LifecycleState.UNSPECIFIED, RolloutStatus.COMPLETED, null),
                    ReleaseSnapshot(
                        ReleaseId(listOf(102), "2.5.0"),
                        LifecycleState.IN_REVIEW,
                        RolloutStatus.UNSPECIFIED,
                        null,
                    ),
                )
        }

    @Test
    fun `an edit without an id is malformed`() =
        runTest {
            play.on("GET", "/tracks/production/releases", ok(fixture("publisher/releases-list.json")))
            play.on("POST", "/edits", ok("""{"expiryTimeSeconds":"1"}"""))

            shouldThrow<MalformedResponseException> { gateway().productionReleases(app) }
        }

    // ---- writes

    @Test
    fun `setRollout changes only the target release's userFraction and keeps every other field`() =
        runTest {
            val before = fixture("publisher/track-with-extra-fields.json")
            editRoutes(before)

            gateway().setRollout(app, v101, 0.2)

            val original = json(before)
            val expected =
                JsonObject(
                    original +
                        (
                            "releases" to
                                kotlinx.serialization.json.JsonArray(
                                    listOf(
                                        withField(original.releases()[0], "userFraction", JsonPrimitive(0.2)),
                                        original.releases()[1],
                                    ),
                                )
                        ),
                )
            json(play.bodies("PUT", "/tracks/production").single()) shouldBe expected
            play.count("POST", ":commit") shouldBe 1
            play.count("DELETE", "/edits/EDIT_ID") shouldBe 0
        }

    @Test
    fun `halt sets status halted and keeps the fraction and release notes`() =
        runTest {
            editRoutes(fixture("publisher/track-with-extra-fields.json"))

            gateway().halt(app, v101)

            val sent = json(play.bodies("PUT", "/tracks/production").single()).releases()[0]
            sent shouldBe
                withField(
                    json(fixture("publisher/track-with-extra-fields.json")).releases()[0],
                    "status",
                    JsonPrimitive("halted"),
                )
        }

    @Test
    fun `complete sets status completed and drops userFraction`() =
        runTest {
            editRoutes(fixture("publisher/track-with-extra-fields.json"))

            gateway().complete(app, v101)

            val original = json(fixture("publisher/track-with-extra-fields.json")).releases()[0]
            json(play.bodies("PUT", "/tracks/production").single()).releases()[0] shouldBe
                JsonObject(original - "userFraction" + ("status" to JsonPrimitive("completed")))
        }

    @Test
    fun `advancing a halted release is refused without writing, and the edit is discarded`() =
        runTest {
            editRoutes(fixture("publisher/track-production.json").replace("\"inProgress\"", "\"halted\""))

            shouldThrow<UnexpectedReleaseStateException> { gateway().setRollout(app, v101, 0.2) }.message shouldContain
                "halted"
            play.count("PUT", "/tracks/production") shouldBe 0
            play.count("POST", ":commit") shouldBe 0
            play.count("DELETE", "/edits/EDIT_ID") shouldBe 1
        }

    @Test
    fun `a release that is no longer on the track is refused`() =
        runTest {
            editRoutes(fixture("publisher/track-production.json"))

            shouldThrow<UnexpectedReleaseStateException> {
                gateway().setRollout(app, ReleaseId(listOf(999), "gone"), 0.2)
            }.message shouldContain "not on production"
            play.count("PUT", "/tracks/production") shouldBe 0
        }

    @Test
    fun `a write that is already in place is not repeated`() =
        runTest {
            editRoutes(fixture("publisher/track-production.json").replace("0.1", "0.5"))

            gateway().setRollout(app, v101, 0.2)

            play.count("PUT", "/tracks/production") shouldBe 0
            play.count("POST", ":commit") shouldBe 0
            play.count("DELETE", "/edits/EDIT_ID") shouldBe 1
        }

    @Test
    fun `after a concurrent commit the adapter re-reads and does not advance twice`() =
        runTest {
            val conflict = json(409, """{"error":{"code":409,"message":"edit conflict","status":"ABORTED"}}""")
            val atTenPercent = fixture("publisher/track-production.json")
            val alreadyAtTwenty = atTenPercent.replace("0.1", "0.2")
            editRoutes(atTenPercent, alreadyAtTwenty, commit = arrayOf(conflict))

            gateway().setRollout(app, v101, 0.2)

            play.count("GET", "/tracks/production") shouldBe 2
            play.count("PUT", "/tracks/production") shouldBe 1
            play.count("POST", ":commit") shouldBe 1
        }

    @Test
    fun `after a conflict that left the track unchanged the adapter writes again from a fresh edit`() =
        runTest {
            val conflict = json(409, """{"error":{"code":409,"status":"ABORTED"}}""")
            val track = fixture("publisher/track-production.json")
            editRoutes(track, track, commit = arrayOf(conflict, ok(fixture("publisher/edits-insert.json"))))

            gateway().setRollout(app, v101, 0.2)

            play.count("POST", "/edits") shouldBe 2
            play.count("POST", ":commit") shouldBe 2
            play.count("DELETE", "/edits/EDIT_ID") shouldBe 1
        }

    @Test
    fun `a conflict that never clears ends with EditConflictException after the attempt budget`() =
        runTest {
            val conflict = json(409, """{"error":{"code":409,"status":"ABORTED"}}""")
            editRoutes(fixture("publisher/track-production.json"), commit = arrayOf(conflict))

            shouldThrow<EditConflictException> { gateway().setRollout(app, v101, 0.2) }
            play.count("POST", ":commit") shouldBe PlayPublisherGateway.DEFAULT_WRITE_ATTEMPTS
        }

    @Test
    fun `a commit that times out is not retried`() =
        runTest {
            editRoutes(
                fixture("publisher/track-production.json"),
                commit = arrayOf(FakePlay.slow(Duration.ofSeconds(3))),
            )

            shouldThrow<WriteOutcomeUnknownException> {
                gateway(
                    timeout = Duration.ofMillis(300),
                ).setRollout(app, v101, 0.2)
            }
            play.count("POST", ":commit") shouldBe 1
            play.count("PUT", "/tracks/production") shouldBe 1
        }

    @Test
    fun `a permission error on commit propagates at once`() =
        runTest {
            editRoutes(
                fixture("publisher/track-production.json"),
                commit = arrayOf(json(403, fixture("publisher/error-permission-denied.json"))),
            )

            val error = shouldThrow<PlayApiException> { gateway().setRollout(app, v101, 0.2) }
            error.apiStatus shouldBe "PERMISSION_DENIED"
            error.message shouldContain "The caller does not have permission"
            play.count("POST", ":commit") shouldBe 1
            play.count("DELETE", "/edits/EDIT_ID") shouldBe 1
        }

    @Test
    fun `a staged fraction outside (0, 1) is rejected before any call`() =
        runTest {
            shouldThrow<IllegalArgumentException> { gateway().setRollout(app, v101, 1.0) }
            play.calls.size shouldBe 0
        }
}
