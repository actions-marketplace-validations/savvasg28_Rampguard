package io.github.savvasg28.rampguard.core.runner

import io.github.savvasg28.rampguard.core.model.Decision
import io.github.savvasg28.rampguard.core.model.LifecycleState
import io.github.savvasg28.rampguard.core.model.RolloutStatus
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.Simulation
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration

class RolloutRunnerTest {
    private val v100 = Fixtures.release(100)
    private val v101 = Fixtures.release(101)
    private val v102 = Fixtures.release(102)

    @Test
    fun `status shows the live release, its stage, the next step and releases waiting for a person`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            sim.play.completedRelease(v100)
            sim.play.humanPublish(v101, 0.05)
            sim.play.humanSubmit(v102, LifecycleState.APPROVED_NOT_PUBLISHED)

            val status = sim.runner.status()

            status.release shouldBe Status.ReleaseStatus("v101", listOf(101L), LifecycleState.PUBLISHED)
            status.playStatus shouldBe RolloutStatus.IN_PROGRESS
            status.fraction shouldBe 0.05
            status.stage shouldBe Status.StageStatus(1, 5)
            status.nextStep shouldBe Status.StageStatus(2, 20)
            status.waiting shouldBe
                listOf(Status.ReleaseStatus("v102", listOf(102L), LifecycleState.APPROVED_NOT_PUBLISHED))
            status.toJson() shouldContain "\"nextStep\": {"
            status.toJson() shouldContain "\"playError\": null"
            sim.audit.records.shouldBeEmpty()
        }

    @Test
    fun `status for a completed track has no next step`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            sim.play.completedRelease(v100)
            val status = sim.runner.status()
            status.release?.name shouldBe "v100"
            status.playStatus shouldBe RolloutStatus.COMPLETED
            status.stage shouldBe null
            status.nextStep shouldBe null
        }

    @Test
    fun `status with Play unreadable still renders`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            sim.play.readFailure = IllegalStateException("503")
            val status = sim.runner.status()
            status.release shouldBe null
            status.playError shouldBe "503"
        }

    @Test
    fun `a stuck Play call times out and is treated as unreadable`() =
        runTest {
            val sim = Simulation(Fixtures.policy())
            val slow =
                object : io.github.savvasg28.rampguard.core.ports.PlayGateway by sim.play {
                    override suspend fun productionReleases(app: io.github.savvasg28.rampguard.core.model.AppId) =
                        sim.play.productionReleases(app).also { delay(Duration.ofMinutes(5).toMillis()) }
                }
            val runner =
                RolloutRunner(
                    policy = Fixtures.policy(),
                    gateway = slow,
                    audit = sim.audit,
                    clock = sim.clock,
                    callTimeout = Duration.ofSeconds(1),
                )
            runner
                .tick()
                .decision
                .shouldBeInstanceOf<Decision.NoOp>()
                .reason shouldContain "could not be read"
        }
}
