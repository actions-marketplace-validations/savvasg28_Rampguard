# 0013: Events leave through one generic webhook

Date: 2026-10-26. Status: accepted.

## Context

Rampguard has no view of app health (ADR 0010), but the tools that do, such as Datadog, Sentry, Grafana and
Crashlytics, cannot show *when* a rollout stage changed unless something tells them. The Slack notifier reaches people
and nothing else. Each tool has its own API shape and its own authentication, and the list of tools grows.

## Decision

Rampguard emits one JSON document per event to a single configurable URL, with optional extra request headers whose
values may come from environment variables, and an HMAC-SHA256 signature of the body when a secret is configured. The
document is a versioned contract: fields are added, never renamed; `event` takes a closed set of names.

Rampguard does not ship a notifier per vendor. A vendor that accepts arbitrary JSON with a header receives the webhook
directly. A vendor that wants its own body shape is reached through a relay, a small function that verifies the
signature and reformats. `examples/events/` holds relays for Datadog, Sentry and Grafana.

## Consequences

- The jar grows by one small adapter and no vendor SDK. Adding a destination means writing a relay, not a release.
- Header values can be API keys, so they are treated like the webhook URL: read from the environment, never logged,
  never placed in an exception, and covered by a test that asserts the absence.
- A notifier failure is logged and never fails the run, because by then the Play write has happened.
- Receivers see every run's events, including the same `ready_to_publish` on consecutive mornings, because runs keep
  no memory (ADR 0011). Dedupe belongs to the receiver.
