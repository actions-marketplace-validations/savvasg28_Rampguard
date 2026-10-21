package io.github.savvasg28.rampguard.core.runner

import io.github.savvasg28.rampguard.core.testing.FakePlayGateway
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GuardedPlayGatewayTest {
    private val release = Fixtures.release(101)

    @Test
    fun `a dry run refuses every write but allows reads`() =
        runTest {
            val fake = FakePlayGateway()
            fake.humanPublish(release, 0.01)
            val guarded = GuardedPlayGateway(fake, dryRun = true)
            guarded.productionReleases(Fixtures.app) shouldHaveSize 1
            shouldThrow<WriteRefusedException> { guarded.setRollout(Fixtures.app, release, 0.05) }.message shouldContain
                "set rollout"
            shouldThrow<WriteRefusedException> { guarded.halt(Fixtures.app, release) }.message shouldContain "halt"
            shouldThrow<WriteRefusedException> { guarded.complete(Fixtures.app, release) }.message shouldContain
                "complete"
            fake.writes.shouldBeEmpty()
        }

    @Test
    fun `a real run passes writes through`() =
        runTest {
            val fake = FakePlayGateway()
            fake.humanPublish(release, 0.01)
            val guarded = GuardedPlayGateway(fake, dryRun = false)
            guarded.setRollout(Fixtures.app, release, 0.05)
            guarded.halt(Fixtures.app, release)
            guarded.complete(Fixtures.app, release)
            fake.writes shouldHaveSize 3
        }
}
