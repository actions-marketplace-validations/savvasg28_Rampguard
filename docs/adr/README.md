# Architecture decision records

Each record explains one decision that shaped the code: the situation, the decision, and its consequences. Numbers are
stable. A gap in the numbering means that record was retired.

| # | Title | Status |
|---|-------|--------|
| [0002](0002-pure-core-with-enforced-dependency-rule.md) | Pure core with an enforced dependency rule | Accepted |
| [0009](0009-play-adapters.md) | The Play adapter calls the REST API directly and edits the raw track | Accepted |
| [0010](0010-scheduled-rollouts-with-external-brakes.md) | Scheduled rollouts with external brakes; no vitals | Accepted. Holds and the soak timer were removed by 0011. |
| [0011](0011-stateless-the-cron-is-the-schedule.md) | Stateless: the cron is the schedule | Accepted. The `mode` key was removed by 0012. |
| [0012](0012-dry-run-is-a-flag-not-a-mode.md) | Dry run is a flag, not a configuration mode | Accepted |
