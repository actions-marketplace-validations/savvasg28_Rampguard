# Rampguard design

This document describes how Rampguard behaves and how the code is organised. It is for contributors. The reasons
behind the decisions are in the [architecture decision records](adr/README.md). The README covers installation.

Rampguard advances a Google Play staged rollout one stage per scheduled run and halts the rollout on command. It keeps
no state between runs and does not judge release health.

## Terms

| Term | Meaning |
|---|---|
| **release** | A version on the Play production track, identified by its set of version codes. |
| **live release** | The one release whose Play status is `inProgress` or `halted`. |
| **stage** | A percentage from the policy's `stages` list. |
| **step** | Setting the live release from its current stage to the next stage. The last stage completes the release. |
| **run** | One execution of a command: `tick`, `halt` or `status`. |
| **policy** | The parsed and validated `rampguard.yml`. |

## Goals and non-goals

Goals:

- Never skip a stage.
- Never write to a halted or completed release, whoever halted or completed it.
- Never write to Play during a dry run.
- Need no infrastructure beyond GitHub Actions and two secrets.

Non-goals:

- Judging release health from vitals or crash data ([ADR 0010](adr/0010-scheduled-rollouts-with-external-brakes.md)).
- Remembering anything between runs, including holds ([ADR 0011](adr/0011-stateless-the-cron-is-the-schedule.md)).
- Uploading builds, creating releases, or pressing the managed-publishing release button. The Play API offers no call
  for the last one.
- Resuming a halted release.
- iOS, and tracks other than production.

## Behaviour

### `tick`

A run reads the production track and applies the first matching row:

| The run sees | The run does |
|---|---|
| The Play API is unreadable | Nothing. Reports a guard error and exits non-zero. |
| No live release | Nothing. Reports releases that are waiting to be published or were rejected. |
| A halted release | Nothing. |
| A live release at or above the second-to-last stage | Completes it. |
| A live release at stage *n*, or between stages *n* and *n+1* | Sets it to stage *n+1*. |

The run steps whatever release is live when it fires. There is no minimum time at a stage. A release published in the
afternoon is stepped the next morning; a release published minutes before the run is stepped minutes later.

A manual run of the tick workflow does exactly what a scheduled run does.

### `halt`

The run halts the live release in Play if the requested `versionCode` is one of its version codes. If the version code
belongs to another release, or the release is already halted, the run does nothing. The `reason` and `source` fields
are untrusted text: the run removes control characters, collapses whitespace and truncates them before use.

### `status`

The run prints the production track as JSON: the live release, its lifecycle and Play status, its fraction, the
stage it is at, the next step a run would take, and releases waiting for a person. The run writes nothing and sends no
notification. Field names are a contract for scripts: add fields, never rename them.

### Dry run

`tick --dry-run` and `halt --dry-run` decide and report but write nothing. The audit line reads
`WOULD Advance(to=20%)`. Two independent checks prevent the write: the runner skips it, and `GuardedPlayGateway`
refuses it ([ADR 0012](adr/0012-dry-run-is-a-flag-not-a-mode.md)).

### Notifications

Each run sends one message per event: a step, a completion, a halt, a release waiting to be published, a rejection,
or a Play failure. Rampguard sends no message for "nothing to do". Because runs keep no memory, a release that waits
for a person is reported on every run that sees it.

Every event goes to the job log and the job summary. A Slack webhook receives it as text. A generic webhook receives it
as one JSON document per event:

| Field | Type | Meaning |
|---|---|---|
| `schema` | number | The document version, currently `1`. It changes only if a field is renamed or removed, which this contract forbids. |
| `event` | string | `advanced`, `completed`, `halted`, `ready_to_publish`, `rejected` or `error`. |
| `app` | string | The package name. |
| `release` | object or null | `name` and `versionCodes` of the release. Null for `error`. |
| `from`, `to` | number or null | Rollout fractions, `0.05` for 5%. Set for `advanced` and `completed`. |
| `reason`, `source` | string or null | Set for `halted`: the sanitised reason and sender of the halt. |
| `message` | string or null | Set for `error`: the failure, without secrets. |
| `at` | string | ISO 8601 instant of the run. |
| `run` | string or null | The GitHub Actions run URL, or null outside Actions. |

The request is a `POST` with `Content-Type: application/json`, the headers from the configuration, and, when a secret
is configured, `X-Rampguard-Signature: sha256=<hex HMAC-SHA256 of the body>`. Receivers that need another shape sit
behind a small relay; `examples/events/` has three. A notifier failure is logged and never fails the run, because the
Play write has already happened by then.

## Architecture

```
GitHub Actions (app repository)
  schedule, workflow_dispatch ──▶ rampguard tick
  repository_dispatch ──────────▶ rampguard halt      (alerting tools, relays, people)
  workflow_dispatch ────────────▶ rampguard status

rampguard
  cli/       command-line entry points, config loading, adapter wiring
  core/      RolloutRunner ─▶ Engine.decide(policy, observation)
             ports: PlayGateway, Notifier, AuditLog, Clock
  adapters/  play (Android Publisher API), notifier-slack, notifier-webhook
```

The modules follow three rules:

- `core` has no I/O dependencies. A Gradle task fails the build if one appears
  ([ADR 0002](adr/0002-pure-core-with-enforced-dependency-rule.md)).
- `Engine.decide` is a pure function of the policy and the observation. It has no clock and no memory, so the same
  inputs always give the same verdict.
- Every call to the outside world goes through a port and carries a timeout.

Two runs for the same app must not overlap, because both would read Play before either writes. The example workflows
share one concurrency group per app. As a second line, the Play adapter re-reads the track inside its edit before it
writes ([ADR 0009](adr/0009-play-adapters.md)).

## Configuration

`.github/rampguard.yml`:

```yaml
app: com.example.app
stages: [1, 5, 20, 50, 100]
notify:                                # optional
  slackWebhookEnv: SLACK_WEBHOOK_URL
  webhook:
    urlEnv: RAMPGUARD_WEBHOOK_URL
    secretEnv: RAMPGUARD_WEBHOOK_SECRET  # optional, enables X-Rampguard-Signature
    headers:                             # optional; env:NAME reads the value from the environment
      DD-API-KEY: env:DD_API_KEY
```

Validation is strict. Unknown keys fail the job before any API call. Keys from earlier designs (`gates`,
`minSoakHours`, `blackout`, `mode`) fail with a message that names the ADR that removed them.

## Failure modes

| Failure | Behaviour |
|---|---|
| Play API unreadable, 5xx, or timeout | No write. Guard error reported. Job exits non-zero. |
| Release not on the track, or not in progress, at write time | Adapter refuses the write. The next run re-reads. |
| Edit conflict with a concurrent change | Adapter re-reads and never advances twice ([ADR 0009](adr/0009-play-adapters.md)). |
| Commit times out | Not retried. The next run re-reads. |
| Write fails | Guard error reported. The next run tries again. |
| Malformed config or command payload | Job fails before any API call. |

## Security

- The Play service account should see one app only, with *View app information* and *Release to production*
  permissions. A dry run needs the same permissions, because nothing in Play distinguishes it until the write.
- Anyone whose token can send `repository_dispatch` to the repository can halt the rollout. Use a fine-grained token
  per sender.
- Secrets never appear in logs, audit output, job summaries or tests. Tests assert this for the Slack webhook URL and
  for the generic webhook URL, headers and secret.
