# Rampguard conventions

This file is for contributors and for AI assistants working in this repository. Rampguard advances Google Play staged
rollouts one stage per scheduled run and halts them on command. It keeps no state. `docs/design.md` describes the
behaviour and `docs/adr/` records the decisions behind the code. Read both before changing behaviour.

## Architecture rules

- `core` has zero I/O dependencies: no HTTP, files, env vars, Google or GitHub libraries. `core/build.gradle.kts`
  has a `checkCoreDependencies` task with an allow-list; `./gradlew check` fails if anything else sneaks in.
- Everything external goes through a port in `core/.../ports/Ports.kt` (`PlayGateway`, `AuditLog`, `Clock`).
  Adapters live under `adapters/`, wiring lives in `cli`. Google types stay inside `adapters/play`; a root Gradle task
  enforces it.
- Rampguard talks to nothing but Play. It reports through the job log, the job summary and GitHub step outputs
  (`cli/.../Sinks.kt`); notifying other tools is the workflow's job (ADR 0013). Do not add notifier adapters.
- The engine (`core/.../engine/Engine.kt`) is a pure, stateless function: `decide(policy, observation)` returns a
  `Verdict`. Never add I/O, a clock or memory between runs there (ADR 0011).
- Inside `core`, errors are values such as `Decision.NoOp` and `PolicyResult.Invalid`. Adapters throw exceptions.
- Every external call goes through `RolloutRunner.timed { }` and therefore carries a timeout.
- Writes to Play are checked twice: the runner skips them on `--dry-run`, and `GuardedPlayGateway` refuses them.
  Keep both.
- When Rampguard cannot read or verify the release (Play unreadable, ambiguous track, halted, completed), it does not
  write.

## Working on core

- Start every behaviour change with a simulator scenario in `core/src/test/.../engine/ScenarioTest.kt`, built on
  `core/src/testFixtures/.../Simulation.kt`, or with a property in `EnginePropertyTest.kt`. Scenario tests assert the
  exact action timeline: timestamp and decision text.
- Reason strings are part of the audit contract. Define them in `engine/Reasons.kt` only.
- `Status` field names and the step output names (`StepOutputs.NAMES` in `cli/.../Sinks.kt`) are contracts for
  external tools and workflows: add fields, never rename them.
- Kover line coverage on core must stay at or above 90%.

## Build and checks

```
./gradlew check            # build, tests, ktlint, detekt, dependency rules, coverage gate
./gradlew ktlintFormat     # fix formatting before committing
```

ktlint uses the `ktlint_official` style. The detekt configuration is `config/detekt/detekt.yml`. Fix the code rather
than widen the configuration. Write comments that say what the code does, not which document asked for it.

## Process

- When you change a design decision, add or amend a record in `docs/adr/`.
- Never let a secret (the service-account JSON) appear in logs, audit output, step outputs or tests.
