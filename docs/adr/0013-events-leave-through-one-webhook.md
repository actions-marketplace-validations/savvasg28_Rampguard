# 0013: Events leave through one generic webhook

Date: 2026-10-26. Status: accepted.

## Context

Rampguard has no view of app health (ADR 0010), but the tools that do, such as Datadog, Sentry, Grafana and
Crashlytics, cannot show *when* a rollout stage changed unless something tells them. The Slack notifier reaches people
and nothing else. Each tool has its own API shape and its own authentication, and the list of tools grows.

## Decision

`notify` is a list of destinations, each named for what it is: `slack` or `webhook`. A webhook destination posts one
JSON document per event to its URL, with optional request headers and an HMAC-SHA256 signature of the body when
`signWith` is set. Any value may be written as `${NAME}`, the convention shells and Compose files use, and is then read
from that environment variable at run time; nothing else in a value is interpreted. Core keeps the reference
unresolved and the CLI resolves it, so core still reads no environment.

The document names what happened in `type`, with a subject and a verb such as `rollout.advanced`, and puts the details
in an object named after the subject: `rollout`, `halt`, `failure`. Percentages use the same unit as `stages`. A field
that does not apply is absent. The rule `status` already uses holds: fields are added, never renamed.

Rampguard does not ship a notifier per vendor. A vendor that accepts arbitrary JSON with a header receives the webhook
directly. A vendor that wants its own body shape is reached through a relay, a small function that verifies the
signature and reformats. `examples/events/` holds relays for Datadog, Sentry and Grafana.

## Consequences

- The jar grows by one small adapter and no vendor SDK. Adding a destination means writing a relay, not a release.
- Several webhooks can be configured at once, so a direct vendor post and a relay can coexist.
- A destination whose variable is not set is skipped, with a line in the job log naming the variable, so a dry run on a
  laptop works without every secret.
- The `v1` configuration key `slackWebhookEnv` no longer parses; the error names the list form. The jar that shipped
  with that key was downloaded by nobody outside the project.
- Header values can be API keys, so they are treated like the webhook URL: read from the environment, never logged,
  never placed in an exception, and covered by a test that asserts the absence.
- A relay must verify the signature before it calls a vendor. The shared verification function has a test that checks
  the same signed body the Kotlin suite asserts, so the two implementations cannot drift apart unnoticed.
- A notifier failure is logged and never fails the run, because by then the Play write has happened.
- Receivers see every run's events, including the same `ready_to_publish` on consecutive mornings, because runs keep
  no memory (ADR 0011). Dedupe belongs to the receiver.
