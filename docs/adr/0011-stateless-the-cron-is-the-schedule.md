# 0011: Stateless: the cron is the schedule

Date: 2026-10-21. Status: accepted. [ADR 0012](0012-dry-run-is-a-flag-not-a-mode.md) later removed the `mode` key
mentioned here.

## Context

ADR 0010 replaced vitals gates with a time schedule (`minSoakHours`, blackout windows) and external brakes (`hold`,
`resume`, `halt`). The soak clock, the holds and the notification dedupe all needed memory between runs. That memory
meant a state file on a git branch, compare-and-swap on push, schema versions and migrations, and a hidden dependency:
delete the branch and Rampguard forgot everything.

The Play API already returns almost everything the engine needs on every call: the live release, its status (in
progress, halted or completed), its fraction, and the lifecycle of releases that are in review or waiting to be
published. The API does not say when a release went live, and it offers nowhere to keep a hold.

## Decision

Rampguard keeps no state. Each scheduled run reads the production track and acts on what it sees.

1. **The cron is the schedule.** A run moves the live release from the configured stage its fraction is at to the
   next configured stage, and completes it at the last stage. A fraction between two stages counts as the lower one.
   `0 9 * * 1-5` means one step every weekday morning. Weekends, holidays and freezes are whatever the cron does not
   cover, or a halt.
2. **A release that is live at run time is stepped.** There is no soak timer and no "adopt first" run. A release
   published in the afternoon is stepped the next morning. A release published minutes before the run is stepped
   minutes later, so the README tells installers to publish after the run. The first step is the smallest one.
3. **Halted and completed releases are left alone**, whoever halted or completed them. Rampguard never resumes a
   release, so it does not need to know who halted it.
4. **The only brake is `halt`**, which Play itself stores. `hold` and `resume` are gone, because a hold that must
   outlive a run has nowhere to live without state. If halting proves too blunt, holds can return as GitHub issues
   (an open issue with a label is a hold), which would be visible and auditable without state in Rampguard.
5. **Notifications are per run, not per transition.** A release waiting for a person is reported on every run that
   sees it. At a daily cadence that is a daily reminder. A Play read failure is reported on every failing run.
6. **A manual run of the tick workflow is an "advance now" button.** It does exactly what the scheduled run does.

## Consequences

- Removed: the state model, the state store port and the git-branch adapter, holds and `resume`, `minSoakHours`,
  blackout windows and the calendar, human-override tracking, notification dedupe, and schema migration.
- Nothing can be lost between runs, because nothing is kept.
- Two runs for the same app must still not overlap, because both would read Play before either writes. The example
  workflows share one concurrency group, and the Play adapter re-reads the track inside its edit before writing.
- This record supersedes ADR 0004 (observe mode and state) and ADR 0008 (state on an orphan branch). Both were
  retired. ADR 0010 stands, except for holds and the soak schedule.
