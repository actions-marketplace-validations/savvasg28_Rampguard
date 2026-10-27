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

class WebhookNotifierTest {
    private val server = MockWebServer().also { it.start() }
    private val clock = MutableClock(Fixtures.monday9)
    private val release = Fixtures.release(52500, "5.25.0")

    @AfterEach
    fun stop() = server.shutdown()

    private fun notifier(
        headers: Map<String, String> = emptyMap(),
        secret: String? = null,
    ) = WebhookNotifier(
        WebhookTarget(server.url("/hooks/SECRETPATH").toString(), headers, secret),
        app = "com.example.app",
        runUrl = "https://github.com/acme/app/actions/runs/1",
        clock = clock,
    )

    @Test
    fun `posts a typed document with only the objects that apply`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(200))

            notifier().notify(RolloutEvent.Advanced(release, 0.05, 0.2))

            val request = server.takeRequest()
            request.getHeader("Content-Type") shouldBe "application/json"
            request.getHeader("User-Agent") shouldBe "rampguard"
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            body.keys shouldBe setOf("type", "app", "occurredAt", "workflowRun", "release", "rollout")
            body["type"]!!.jsonPrimitive.content shouldBe "rollout.advanced"
            body["rollout"]!!.jsonObject["fromPercent"]!!.jsonPrimitive.content shouldBe "5.0"
            body["rollout"]!!.jsonObject["toPercent"]!!.jsonPrimitive.content shouldBe "20.0"
            body["occurredAt"]!!.jsonPrimitive.content shouldBe "2026-03-02T09:00:00Z"
            body["release"]!!.jsonObject["versionCodes"].toString() shouldBe "[52500]"
        }

    @Test
    fun `every event kind maps to a type and its detail object`() {
        val at = Fixtures.monday9
        WebhookEvent.of("a", RolloutEvent.Completed(release, 0.5), at, null).let {
            it.type shouldBe "rollout.completed"
            it.rollout shouldBe WebhookEvent.Rollout(50.0, 100.0)
        }
        WebhookEvent.of("a", RolloutEvent.Halted(release, "crash spike", "sentry"), at, null).let {
            it.type shouldBe "rollout.halted"
            it.halt shouldBe WebhookEvent.Halt("crash spike", "sentry")
            it.rollout shouldBe null
        }
        WebhookEvent.of("a", RolloutEvent.ReadyToPublish(release), at, null).type shouldBe "release.readyToPublish"
        WebhookEvent.of("a", RolloutEvent.Rejected(release), at, null).type shouldBe "release.rejected"
        WebhookEvent.of("a", RolloutEvent.GuardError("boom"), at, null).let {
            it.type shouldBe "run.failed"
            it.failure shouldBe WebhookEvent.Failure("boom")
            it.release shouldBe null
            it.toJson() shouldBe
                """{"type":"run.failed","app":"a","occurredAt":"2026-03-02T09:00:00Z","failure":{"message":"boom"}}"""
        }
    }

    @Test
    fun `custom headers are sent and the body is signed with the secret`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse().setResponseCode(204))

            notifier(headers = mapOf("DD-API-KEY" to "abc"), secret = "s3cret").notify(RolloutEvent.Rejected(release))

            val request = server.takeRequest()
            request.getHeader("DD-API-KEY") shouldBe "abc"
            request.getHeader(WebhookTarget.SIGNATURE_HEADER) shouldBe
                WebhookTarget.signature(WebhookTarget("http://x", secret = "s3cret").key!!, request.body.readUtf8())
        }

    /** Also checked by `examples/events/verify.test.js`, so relays built on `verify.js` accept this signature. */
    @Test
    fun `the signature matches the shared test vector`() {
        val body =
            WebhookEvent
                .of(
                    "com.example.app",
                    RolloutEvent.Advanced(release, 0.05, 0.2),
                    Fixtures.monday9,
                    null,
                ).toJson()
        body shouldBe SharedVector.BODY
        WebhookTarget.signature(WebhookTarget("http://x", secret = SharedVector.SECRET).key!!, body) shouldBe
            SharedVector.SIGNATURE
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
