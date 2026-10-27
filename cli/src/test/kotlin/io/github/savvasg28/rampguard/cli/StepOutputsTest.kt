package io.github.savvasg28.rampguard.cli

import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.DecisionRecord
import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StepOutputsTest {
    private val live = Fixtures.release(101, "5.25.0")
    private val waiting = Fixtures.release(102, "5.26.0")

    private fun record(
        events: List<RolloutEvent>,
        applied: Boolean?,
        decision: Decision = Decision.NoOp("test"),
    ) = DecisionRecord(
        at = Fixtures.monday9,
        app = Fixtures.app,
        dryRun = applied == false,
        command = "tick",
        release = live,
        observedFraction = 0.05,
        stageIndex = 1,
        decision = decision,
        notes = emptyList(),
        events = events,
        applied = applied,
    )

    @Test
    fun `every output is always present and the names are stable`() {
        StepOutputs.of(record(emptyList(), applied = null)).keys.toList() shouldBe StepOutputs.NAMES
    }

    @Test
    fun `a step that was written reports advanced with its percentages`() {
        StepOutputs.of(record(listOf(RolloutEvent.Advanced(live, 0.05, 0.2)), applied = true)) shouldContainAll
            mapOf(
                "event" to "advanced",
                "release-name" to "5.25.0",
                "version-codes" to "101",
                "from-percent" to "5",
                "to-percent" to "20",
            )
        StepOutputs.of(record(listOf(RolloutEvent.Completed(live, 0.5)), applied = true)) shouldContainAll
            mapOf("event" to "completed", "from-percent" to "50", "to-percent" to "100")
    }

    @Test
    fun `a dry run or a failed write never reports a step`() {
        StepOutputs.of(record(listOf(RolloutEvent.Advanced(live, 0.05, 0.2)), applied = false)) shouldContainAll
            mapOf("event" to "none", "from-percent" to "", "to-percent" to "")
        val failed =
            listOf(RolloutEvent.Advanced(live, 0.05, 0.2), RolloutEvent.GuardError("write to Play failed: 409"))
        StepOutputs.of(record(failed, applied = false))["event"] shouldBe "failed"
    }

    @Test
    fun `a halt carries its reason and source`() {
        StepOutputs.of(
            record(listOf(RolloutEvent.Halted(live, "crash spike", "sentry")), applied = true),
        ) shouldContainAll
            mapOf("event" to "halted", "reason" to "crash spike", "source" to "sentry")
    }

    @Test
    fun `releases waiting for a person are reported next to whatever else happened`() {
        val events = listOf(RolloutEvent.ReadyToPublish(waiting), RolloutEvent.Advanced(live, 0.05, 0.2))
        StepOutputs.of(record(events, applied = true)) shouldContainAll
            mapOf("event" to "advanced", "ready-to-publish" to "5.26.0", "rejected" to "")
        StepOutputs.of(record(listOf(RolloutEvent.Rejected(waiting)), applied = null)) shouldContainAll
            mapOf("event" to "none", "rejected" to "5.26.0")
    }
}
