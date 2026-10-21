package io.github.savvasg28.rampguard.adapters.slack

import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SlackNotifierTest {
    private val server = MockWebServer().also { it.start() }
    private val secretPath = "/services/T000/B000/SECRETTOKEN"
    private val webhook get() = server.url(secretPath).toString()

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `posts one JSON message per event to the webhook`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

            SlackNotifier(webhook, "com.example.app").notify(RolloutEvent.Advanced(Fixtures.release(101), 0.05, 0.2))

            val request = server.takeRequest()
            request.path shouldBe secretPath
            request.getHeader("Content-Type") shouldBe "application/json"
            Json
                .parseToJsonElement(request.body.readUtf8())
                .jsonObject["text"]!!
                .jsonPrimitive.content shouldBe
                ":arrow_up: *com.example.app* v101: 5% → 20%"
        }

    @Test
    fun `a rejected post fails without leaking the webhook URL`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(403).setBody("invalid_token"))

            val e =
                shouldThrow<SlackException> { SlackNotifier(webhook, "app").notify(RolloutEvent.GuardError("boom")) }

            e.message shouldContain "403"
            e.message shouldContain "invalid_token"
            e.message shouldNotContain "SECRETTOKEN"
            e.message shouldNotContain server.hostName
        }

    @Test
    fun `every event has a message`() {
        val release = Fixtures.release(101)
        SlackMessages.text("app", RolloutEvent.Completed(release, 0.5)) shouldContain "50% → 100%"
        SlackMessages.text(
            "app",
            RolloutEvent.Halted(release, "halt requested by datadog: spike", "datadog"),
        ) shouldContain
            "halted"
        SlackMessages.text("app", RolloutEvent.ReadyToPublish(release)) shouldContain "waiting to be published"
        SlackMessages.text("app", RolloutEvent.Rejected(release)) shouldContain "not approved"
        SlackMessages.text("app", RolloutEvent.GuardError("Play down")) shouldContain "Play down"
    }
}
