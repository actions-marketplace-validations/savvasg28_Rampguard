package io.github.savvasg28.rampguard.adapters.webhook

/** Test vector shared with `examples/events/verify.test.js`. Change both together. */
object SharedVector {
    const val SECRET = "shared-test-secret"
    const val BODY =
        """{"type":"rollout.advanced","app":"com.example.app","occurredAt":"2026-03-02T09:00:00Z",""" +
            """"release":{"name":"5.25.0","versionCodes":[52500]},"rollout":{"fromPercent":5.0,"toPercent":20.0}}"""
    const val SIGNATURE = "sha256=5bb177d1f1da59f0ba43406d616847d19ad3d67fd6b0ded263c221b31b57c58e"
}
