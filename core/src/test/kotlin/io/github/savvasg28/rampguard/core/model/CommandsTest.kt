package io.github.savvasg28.rampguard.core.model

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import org.junit.jupiter.api.Test

class CommandsTest {
    @Test
    fun `control characters are stripped and whitespace collapsed`() {
        HaltRequest(1, "crash\u0000 rate\n\n\tspiked\u001b[31m", "data\rdog").sanitised() shouldBe
            HaltRequest(1, "crash rate spiked [31m", "data dog")
    }

    @Test
    fun `oversized fields are truncated and blanks get defaults`() {
        val long = "x".repeat(1_000)
        val s = HaltRequest(1, long, long).sanitised()
        s.reason shouldHaveLength Sanitise.MAX_REASON_LENGTH
        s.source shouldHaveLength Sanitise.MAX_SOURCE_LENGTH
        HaltRequest(1, " \u0007 ", "").sanitised() shouldBe HaltRequest(1, "no reason given", "unknown")
    }

    @Test
    fun `percent formatting is exact for stage values`() {
        percent(0.01) shouldBe "1%"
        percent(0.05) shouldBe "5%"
        percent(0.2) shouldBe "20%"
        percent(1.0) shouldBe "100%"
        percent(0.1234) shouldBe "12.34%"
    }

    @Test
    fun `release identity is the set of version codes`() {
        ReleaseId(listOf(1L, 2L), "a").sameAs(ReleaseId(listOf(2L, 1L), "b")) shouldBe true
        ReleaseId(listOf(1L), "a").sameAs(ReleaseId(listOf(2L), "a")) shouldBe false
        ReleaseId(listOf(1L), "a").sameAs(null) shouldBe false
        ReleaseId(listOf(3L, 9L), "x").maxVersionCode shouldBe 9L
        ReleaseId(listOf(3L, 9L), "x").contains(3L) shouldBe true
    }
}
