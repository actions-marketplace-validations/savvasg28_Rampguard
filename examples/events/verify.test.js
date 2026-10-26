// Run with: node --test examples/events/verify.test.js
// The body and signature are the vector that the Kotlin test suite also asserts (SharedVector.kt), so a relay built on
// verify.js accepts exactly what Rampguard sends.

import { test } from "node:test";
import assert from "node:assert/strict";
import { verifyRampguard } from "./verify.js";

const secret = "shared-test-secret";
const body =
  '{"type":"rollout.advanced","app":"com.example.app","occurredAt":"2026-03-02T09:00:00Z",' +
  '"release":{"name":"5.25.0","versionCodes":[52500]},"rollout":{"fromPercent":5.0,"toPercent":20.0}}';
const signature = "sha256=5bb177d1f1da59f0ba43406d616847d19ad3d67fd6b0ded263c221b31b57c58e";

const request = (sig) =>
  new Request("https://relay.example/", { method: "POST", body, headers: sig ? { "X-Rampguard-Signature": sig } : {} });

test("accepts the signature Rampguard produces", async () => {
  const doc = await verifyRampguard(request(signature), secret);
  assert.equal(doc.type, "rollout.advanced");
  assert.deepEqual(doc.release, { name: "5.25.0", versionCodes: [52500] });
});

test("rejects a missing, malformed or forged signature", async () => {
  assert.equal(await verifyRampguard(request(null), secret), null);
  assert.equal(await verifyRampguard(request("sha256=00"), secret), null);
  assert.equal(await verifyRampguard(request(signature), "another-secret"), null);
});
