package io.github.savvasg28.rampguard.core.audit

import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.Simulation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DeterminismTest {
    private val v101 = Fixtures.release(101)

    private suspend fun run(dryRun: Boolean): String {
        val sim = Simulation(Fixtures.policy(), dryRun = dryRun)
        sim.play.humanPublish(v101, 0.01)
        sim.run()
        sim.nextDay()
        sim.play.humanSetFraction(v101, 0.3)
        sim.nextDay()
        sim.halt(101, reason = "crash alert")
        return sim.auditText()
    }

    @Test
    fun `running the same scenario twice gives byte-identical audit output`() =
        runTest {
            val first = run(dryRun = false)
            first.toByteArray() contentEquals run(dryRun = false).toByteArray() shouldBe true
            first shouldContain "release: v101[101] at 1% stage=0"
            first shouldContain "decision: Advance(to=5%) applied=true"
            first shouldContain "note: fraction 30% is not a configured stage; treating it as the 20% stage"
            first shouldContain "halt com.example.app\n"
            first shouldContain "decision: Halt(halt requested by datadog: crash alert) applied=true"
            first shouldContain "notify: halted:v101[101]:datadog"
        }

    @Test
    fun `a dry run renders proposals as WOULD and never as applied`() =
        runTest {
            val text = run(dryRun = true)
            text shouldContain "decision: WOULD Advance(to=5%) applied=false"
            text shouldContain "decision: WOULD Halt("
            text shouldNotContain "applied=true"
            text.lines().first() shouldBe "2026-03-02T09:00:00Z tick com.example.app dry-run"
        }
}
