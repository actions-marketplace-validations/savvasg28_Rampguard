# Event recipes

Rampguard posts one JSON document per event to each `webhook` destination in `notify`. This page shows how to turn those documents
into records in the tools that already hold your release and crash data: Datadog events, Sentry releases and Grafana
annotations. Each recipe is a relay, a small function that receives the document, verifies the signature and calls the
vendor API. Datadog also works without a relay, through log intake.

For halting a rollout from these tools, see [`examples/halt/`](../halt/README.md).

## The document

```json
{"type": "rollout.advanced",
 "app": "com.example.app",
 "release": {"name": "5.25.0", "versionCodes": [52500]},
 "rollout": {"fromPercent": 5, "toPercent": 20},
 "occurredAt": "2026-10-28T09:17:41Z",
 "workflowRun": "https://github.com/acme/app/actions/runs/123"}
```

| `type` | Also present |
|---|---|
| `rollout.advanced` | `release`, `rollout.fromPercent`, `rollout.toPercent` |
| `rollout.completed` | `release`, `rollout` with `toPercent` 100 |
| `rollout.halted` | `release`, `halt.reason`, `halt.source` |
| `release.readyToPublish` | `release` |
| `release.rejected` | `release` |
| `run.failed` | `failure.message` |

`workflowRun` is present inside GitHub Actions. A field that does not apply is absent. Fields are added, never renamed.

## The signature

When a webhook destination has `signWith`, each request carries
`X-Rampguard-Signature: sha256=<hex HMAC-SHA256 of the raw body>`. A relay must verify this header before it calls a
vendor API, otherwise anyone who finds the relay URL can write to your Datadog, Sentry or Grafana. The recipes share
one verification function, [`verify.js`](verify.js). Its test, [`verify.test.js`](verify.test.js), checks the same
signed body that Rampguard's own test suite produces, so the two sides cannot drift apart unnoticed. Run it with
`node --test examples/events/verify.test.js`.

## Configuration

```yaml
notify:
  - webhook:
      url: ${RAMPGUARD_WEBHOOK_URL}
      signWith: ${RAMPGUARD_WEBHOOK_SECRET}
```

Set the `RAMPGUARD_WEBHOOK_URL` secret to the relay URL and `RAMPGUARD_WEBHOOK_SECRET` to a random string. The relay
holds the same secret plus the vendor API key. Rampguard never sees the vendor key.

## Recipes

| Receiver | What appears there | File |
|---|---|---|
| Datadog, without a relay | A log per event with every field as an attribute, for log search and log monitors | [`datadog-direct.md`](datadog-direct.md) |
| Datadog, through a relay | A titled, tagged event on the event stream | [`datadog-relay.js`](datadog-relay.js) |
| Sentry | A deploy per step on the release, so the release page shows when each stage started | [`sentry-relay.js`](sentry-relay.js) |
| Grafana | An annotation per step, so crash and latency dashboards show the rollout stages as vertical lines | [`grafana-relay.js`](grafana-relay.js) |

The relays are Cloudflare Workers because a Worker is one file with no build step. The same code runs on a Cloud
Function or a Lambda after changing the request and response objects.
