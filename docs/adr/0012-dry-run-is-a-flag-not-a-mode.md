# 0012: Dry run is a flag, not a configuration mode

Date: 2026-10-21. Status: accepted.

## Context

Earlier designs had `mode: observe | enforce` in the configuration file. A team could watch the engine's judgement
calls (gate outcomes, soak timing) for a whole rollout before letting it write. After ADR 0011 the engine makes no
judgement calls: a run steps to the next stage. Watching that for weeks shows the same line every morning.

## Decision

There is no mode. `tick` and `halt` accept `--dry-run`, and the action accepts a `dry-run` input. A dry run decides
and reports but writes nothing. Its purpose is to check an installation once: credentials, permissions,
configuration, workflow wiring and Slack.

The two-check guarantee is unchanged: the runner skips writes on a dry run, and `GuardedPlayGateway` refuses them.

## Consequences

- The configuration file holds `app`, `stages` and `notify`. A `mode` key is rejected with a pointer to this record.
- The kill switch is disabling the workflow in the Actions tab, which GitHub already provides.
- A dry run needs the same Play permissions as a real run, because nothing in Play distinguishes them until the
  write.
