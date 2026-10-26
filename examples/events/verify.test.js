// Run with: node --test examples/events/verify.test.js
// The body and signature are the vector that the Kotlin test suite also asserts (SharedVector.kt), so a relay built on
// verify.js accepts exactly what Rampguard sends.

import { test } from "node:test";
import assert from "node:assert/strict";
import { verifyRampguard } from "./verify.js";

const secret = "shared-test-secret";
const body =
  '{"event":"advanced","app":"com.example.app","at":"2026-03-02T09:00:00Z","run":null,' +
  '"release":{"name":"5.25.0","versionCodes":[52500]},"from":0.05,"to":0.2,"reason":null,"source":null,"message":null}';
const signature = "sha256=339b8b19441cd4e36b380b069e51461b1401f9a2cf18a43bf07e53140f86e3e6";

const request = (sig) =>
  new Request("https://relay.example/", { method: "POST", body, headers: sig ? { "X-Rampguard-Signature": sig } : {} });

test("accepts the signature Rampguard produces", async () => {
  const doc = await verifyRampguard(request(signature), secret);
  assert.equal(doc.event, "advanced");
  assert.deepEqual(doc.release, { name: "5.25.0", versionCodes: [52500] });
});

test("rejects a missing, malformed or forged signature", async () => {
  assert.equal(await verifyRampguard(request(null), secret), null);
  assert.equal(await verifyRampguard(request("sha256=00"), secret), null);
  assert.equal(await verifyRampguard(request(signature), "another-secret"), null);
});
