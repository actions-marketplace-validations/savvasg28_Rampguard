package io.github.savvasg28.rampguard.adapters.webhook

import io.github.savvasg28.rampguard.core.model.RolloutEvent
import io.github.savvasg28.rampguard.core.testing.Fixtures
import io.github.savvasg28.rampguard.core.testing.MutableClock
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
import javax.crypto.spec.SecretKeySpec

class WebhookNotifierTest {
    private val server = MockWebServer().also { it.start() }
    private val url get() = server.url("/hooks/SECRETPATH").toString()
    private val clock = MutableClock(Fixtures.monday9)
    private val release = Fixtures.release(101)

    @AfterEach
    fun stop() = server.shutdown()

    private fun notifier(
        headers: Map<String, String> = emptyMap(),
        secret: String? = null,
    ) = WebhookNotifier(
        url,
        "com.example.app",
        clock,
        headers,
        secret,
        runUrl = "https://github.com/acme/app/actions/runs/1",
    )

    @Test
    fun `posts the event document with every key present`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(200))

            notifier().notify(RolloutEvent.Advanced(release, 0.05, 0.2))

            val request = server.takeRequest()
            request.getHeader("Content-Type") shouldBe "application/json"
            request.getHeader("User-Agent") shouldBe "rampguard"
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            body.keys shouldBe
                setOf("schema", "event", "app", "release", "from", "to", "reason", "source", "message", "at", "run")
            body["event"]!!.jsonPrimitive.content shouldBe "advanced"
            body["from"]!!.jsonPrimitive.content shouldBe "0.05"
            body["to"]!!.jsonPrimitive.content shouldBe "0.2"
            body["at"]!!.jsonPrimitive.content shouldBe "2026-03-02T09:00:00Z"
            body["release"]!!.jsonObject["versionCodes"].toString() shouldBe "[101]"
            body["reason"].toString() shouldBe "null"
        }

    @Test
    fun `every event kind maps to its name`() {
        val at = Fixtures.monday9
        WebhookEvent.of("a", RolloutEvent.Completed(release, 0.5), at, null).let {
            it.event shouldBe "completed"
            it.to shouldBe
                1.0
        }
        WebhookEvent.of("a", RolloutEvent.Halted(release, "r", "sentry"), at, null).let {
            it.event shouldBe "halted"
            it.source shouldBe
                "sentry"
        }
        WebhookEvent.of("a", RolloutEvent.ReadyToPublish(release), at, null).event shouldBe "ready_to_publish"
        WebhookEvent.of("a", RolloutEvent.Rejected(release), at, null).event shouldBe "rejected"
        WebhookEvent.of("a", RolloutEvent.GuardError("boom"), at, null).let {
            it.event shouldBe "error"
            it.message shouldBe
                "boom"
            it.release shouldBe null
        }
    }

    @Test
    fun `custom headers are sent and the body is signed with the secret`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(204))

            notifier(headers = mapOf("DD-API-KEY" to "abc"), secret = "s3cret").notify(RolloutEvent.Rejected(release))

            val request = server.takeRequest()
            request.getHeader("DD-API-KEY") shouldBe "abc"
            val body = request.body.readUtf8()
            request.getHeader(WebhookNotifier.SIGNATURE_HEADER) shouldBe
                "sha256=" + WebhookNotifier.sign(SecretKeySpec("s3cret".toByteArray(), "HmacSHA256"), body)
        }

    @Test
    fun `a rejected post fails without leaking the URL or headers`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))

            val e =
                shouldThrow<WebhookException> {
                    notifier(
                        headers = mapOf("DD-API-KEY" to "abc"),
                    ).notify(RolloutEvent.GuardError("x"))
                }

            e.message shouldContain "401"
            e.message shouldContain "bad key"
            e.message shouldNotContain "SECRETPATH"
            e.message shouldNotContain "abc"
            e.message shouldNotContain server.hostName
        }
}
