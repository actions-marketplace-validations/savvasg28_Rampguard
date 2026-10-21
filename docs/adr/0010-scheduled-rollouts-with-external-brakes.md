# 0010: Scheduled rollouts with external brakes; no vitals

Date: 2026-10-21. Status: accepted. [ADR 0011](0011-stateless-the-cron-is-the-schedule.md) later removed the soak
timer, blackout windows and holds described here.

## Context

The first design gated every advance on Play vitals from the Developer Reporting API. Measurements against a
production app on 20 and 21 October 2026 showed three problems:

- **The data was 4 to 6 days behind.** HOURLY data lagged 135 to 141 hours and DAILY data 105 to 120 hours. HOURLY
  was older than DAILY, the opposite of what the API reference describes. Google's documentation commits to no delay.
- **Even the best case is slow.** With DAILY data refreshed once a day, a gate needs one fresh day per stage, so a
  1% to 100% ramp takes a week or more. The `minSample` guard kept early stages holding: the app had about 60 daily
  users at 1%.
- **The data is coarse.** `distinctUsers` is rounded to the nearest 10, 100 or 1,000. The rate metrics are documented
  as percentages but arrive as fractions.

Data that is late, inconsistent and coarse cannot decide whether a release is safe to advance. As a halt signal it
would act on releases that humans had moved past days earlier.

## Decision

Rampguard advances staged rollouts on a schedule. Anything outside Rampguard can hold, resume or halt them.

1. **No vitals, in any form.** No gates, no signal source, no metric configuration. Rampguard does not call the
   Reporting API.
2. **Advance on time alone.** A release moves to the next stage after `minSoakHours` at the current stage, outside
   blackout windows, unless it is held, halted or taken over by a human. The last stage completes the release.
3. **External brakes are commands.** `hold`, `resume` and `halt` can be sent from `repository_dispatch` (alerting
   tools, relays, watchdogs), from `workflow_dispatch`, or from the CLI. Rampguard does not decide why; it carries
   out the request safely and idempotently and reports what it did.
4. **A hold never expires.** It lasts until a matching `resume`. A sender that wants expiry sends `resume` itself.
5. **Resume does not restart the soak clock.** Time spent held counts as time at the stage, so a sender that
   alternates hold and resume cannot push the rollout back indefinitely.
6. **Sender liveness is out of scope.** A dead-man's switch or heartbeat belongs in front of Rampguard. A stable
   command contract, idempotent commands and a machine-readable `status` make one easy to build.

## Consequences

- The meaning of "fail safe" changed. Before, missing data held the rollout. Now the default is to advance on
  schedule, and only an unreadable or ambiguous release prevents a write. Teams that want "stop unless healthy" put a
  watchdog in front that sends `hold`.
- `core` lost the signal source, readings, gates and the gate evaluator, and gained hold state and the `hold` and
  `resume` decisions.
- `adapters/play` kept the publisher gateway. The reporting adapter and the recording tooling were removed; they
  remain in git history.
- The closest existing tool became the Playtomic Bitrise step, which auto-advances without gates. Rampguard differs by
  its brake contract, human-override detection, blackouts, field-preserving writes and audit trail, with no
  infrastructure.
