# Event recipes

Rampguard posts one JSON document per event to the URL in `notify.webhook`. This page shows how to turn those documents
into records in the tools that already hold your release and crash data: Datadog events, Sentry releases and Grafana
annotations. Each recipe is a relay, a small function that receives the document, verifies the signature and calls the
vendor API. Datadog also works without a relay, through log intake.

For halting a rollout from these tools, see [`examples/halt/`](../halt/README.md).

## The document

```json
{"schema": 1, "event": "advanced", "app": "com.example.app",
 "release": {"name": "5.25.0", "versionCodes": [52500]},
 "from": 0.05, "to": 0.2, "reason": null, "source": null, "message": null,
 "at": "2026-10-28T09:17:41Z", "run": "https://github.com/acme/app/actions/runs/123"}
```

| Field | Meaning |
|---|---|
| `event` | `advanced`, `completed`, `halted`, `ready_to_publish`, `rejected` or `error`. |
| `release` | `name` and `versionCodes`. Null for `error`. |
| `from`, `to` | Rollout fractions, `0.2` for 20%. Set for `advanced` and `completed`. |
| `reason`, `source` | Set for `halted`. |
| `message` | Set for `error`. |
| `at` | When the run happened. |
| `run` | The GitHub Actions run, or null when Rampguard runs elsewhere. |

Every document has every key; absent values are `null`. Fields are added, never renamed.

## The signature

When `notify.webhook.secretEnv` names a secret, each request carries
`X-Rampguard-Signature: sha256=<hex HMAC-SHA256 of the raw body>`. A relay must verify this header before it calls a
vendor API, otherwise anyone who finds the relay URL can write to your Datadog, Sentry or Grafana. The recipes share
one verification function, [`verify.js`](verify.js).

## Configuration

```yaml
notify:
  webhook:
    urlEnv: RAMPGUARD_WEBHOOK_URL
    secretEnv: RAMPGUARD_WEBHOOK_SECRET
```

Set `RAMPGUARD_WEBHOOK_URL` to the relay URL and `RAMPGUARD_WEBHOOK_SECRET` to a random string. The relay holds the
same secret plus the vendor API key. Rampguard never sees the vendor key.

## Recipes

| Receiver | What appears there | File |
|---|---|---|
| Datadog, without a relay | A log per event with every field as an attribute, for log search and log monitors | [`datadog-direct.md`](datadog-direct.md) |
| Datadog, through a relay | A titled, tagged event on the event stream | [`datadog-relay.js`](datadog-relay.js) |
| Sentry | A release per version code with a deploy per step, so Sentry's release health shows when each stage started | [`sentry-relay.js`](sentry-relay.js) |
| Grafana | An annotation per step, so crash and latency dashboards show the rollout stages as vertical lines | [`grafana-relay.js`](grafana-relay.js) |

The relays are Cloudflare Workers because a Worker is one file with no build step. The same code runs on a Cloud
Function or a Lambda after changing the request and response objects.
