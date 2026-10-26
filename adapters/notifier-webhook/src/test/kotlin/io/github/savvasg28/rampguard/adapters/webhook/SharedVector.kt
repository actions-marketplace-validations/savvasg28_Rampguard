package io.github.savvasg28.rampguard.adapters.webhook

/** Test vector shared with `examples/events/verify.test.js`. Change both together. */
object SharedVector {
    const val SECRET = "shared-test-secret"
    const val BODY =
        """{"event":"advanced","app":"com.example.app","at":"2026-03-02T09:00:00Z","run":null,""" +
            """"release":{"name":"5.25.0","versionCodes":[52500]},""" +
            """"from":0.05,"to":0.2,"reason":null,"source":null,"message":null}"""
    const val SIGNATURE = "sha256=339b8b19441cd4e36b380b069e51461b1401f9a2cf18a43bf07e53140f86e3e6"
}
