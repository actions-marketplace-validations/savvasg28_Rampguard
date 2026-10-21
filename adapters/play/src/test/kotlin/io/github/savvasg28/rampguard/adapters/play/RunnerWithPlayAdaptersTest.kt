package io.github.savvasg28.rampguard.adapters.play

import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.fixture
import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.noContent
import io.github.savvasg28.rampguard.adapters.play.FakePlay.Companion.ok
import io.github.savvasg28.rampguard.adapters.play.publisher.PlayPublisherGateway
import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.HaltRequest
import io.github.savvasg28.rampguard.core.runner.RolloutRunner
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.MutableClock
import io.github.savvasg28.rampguard.core.testing.RecordingAuditLog
import io.github.savvasg28.rampguard.core.testing.RecordingNotifier
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The core runner driving the real publisher adapter against a local fake: the adapter satisfies the port the way
 * the engine expects, and a dry run never writes. Uses runBlocking because real HTTP calls are in flight.
 */
class RunnerWithPlayAdaptersTest {
    private val play = FakePlay()

    @AfterEach
    fun stop() = play.close()

    /** The fixture track has v101 in progress at 10%, which sits between the 5% and 20% stages. */
    private fun runner(dryRun: Boolean): RolloutRunner {
        play.on("GET", "/tracks/production/releases", ok(fixture("publisher/releases-list.json")))
        play.on("POST", "/edits", ok(fixture("publisher/edits-insert.json")))
        play.on("GET", "/edits/EDIT_ID/tracks/production", ok(fixture("publisher/track-production.json")))
        play.on("PUT", "/edits/EDIT_ID/tracks/production", ok(fixture("publisher/track-production.json")))
        play.on("POST", "/edits/EDIT_ID:commit", ok(fixture("publisher/edits-insert.json")))
        play.on("DELETE", "/edits/EDIT_ID", noContent())
        return RolloutRunner(
            policy = Fixtures.policy(),
            gateway = PlayPublisherGateway(play.http(), play.endpoints),
            notifier = RecordingNotifier(),
            audit = RecordingAuditLog(),
            clock = MutableClock(Instant.parse("2026-03-12T09:00:00Z")),
            dryRun = dryRun,
        )
    }

    @Test
    fun `a run steps the live release to the next stage through the edit flow`() =
        runBlocking<Unit> {
            val outcome = runner(dryRun = false).tick()

            outcome.decision shouldBe Decision.Advance(2, 0.2)
            outcome.record.applied shouldBe true
            play.bodies("PUT", "/tracks/production").single() shouldContain "\"userFraction\":0.2"
            play.count("POST", ":commit") shouldBe 1
        }

    @Test
    fun `a dry run only would step`() =
        runBlocking<Unit> {
            val outcome = runner(dryRun = true).tick()

            outcome.decision shouldBe Decision.Advance(2, 0.2)
            outcome.record.applied shouldBe false
            play.count("PUT", "/tracks/production") shouldBe 0
            play.count("POST", ":commit") shouldBe 0
        }

    @Test
    fun `a halt command halts through the edit flow`() =
        runBlocking<Unit> {
            val outcome = runner(dryRun = false).halt(HaltRequest(101, "crash alert", "datadog"))

            outcome.decision.shouldBeInstanceOf<Decision.Halt>()
            outcome.record.applied shouldBe true
            play.bodies("PUT", "/tracks/production").single() shouldContain "\"halted\""
        }
}
