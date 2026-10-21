# Halt recipes

A halt is one HTTP request: a `repository_dispatch` event sent to the app repository. The command workflow
(`examples/workflows/rollout-command.yml`) receives it and runs `rampguard halt`. This page shows how to send that
request from the tools that usually know first that a release is bad.

## The request

```
POST https://api.github.com/repos/OWNER/REPO/dispatches
Authorization: Bearer <token>
Accept: application/vnd.github+json

{"event_type": "rollout-halt",
 "client_payload": {"source": "sentry", "reason": "crash spike", "versionCode": 101}}
```

| Field | Meaning |
|---|---|
| `event_type` | Always `rollout-halt`. |
| `client_payload.versionCode` | The version code of the release to halt. Rampguard ignores the request if it is not the live release, so a late alert cannot halt the next release. Digits only; the action strips anything else, so `version_code:101` also works. |
| `client_payload.reason` | Free text, shown in the notification. Truncated to 200 characters. |
| `client_payload.source` | Who sent it. Truncated to 64 characters. |

### The token

Create a fine-grained personal access token, or a GitHub App token, with **Contents: read and write** on the app
repository only. That is the permission `repository_dispatch` needs. Make one token per sender so that you can revoke
one without touching the others. Anyone who holds the token can halt the rollout; treat it like a deploy key.

## Recipes

| Sender | File | Status |
|---|---|---|
| A person or a script | [`curl.sh`](curl.sh) | Tested |
| Datadog monitor | [`datadog-webhook.md`](datadog-webhook.md) | Untested |
| Sentry issue alert, through a relay | [`sentry-relay.js`](sentry-relay.js) | Untested |

Datadog can call GitHub directly because its webhooks allow custom headers. Sentry's cannot carry an `Authorization`
header, so a small relay signs the request on its behalf. The relay pattern works for any tool that can only post a
fixed webhook.

When you test a recipe for real, change its status here.
