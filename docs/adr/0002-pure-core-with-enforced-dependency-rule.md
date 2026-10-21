# 0002: Pure core with an enforced dependency rule

Date: 2026-10-20. Status: accepted.

## Context

The `core` module holds the decision engine. The engine must be testable with fakes alone, and a hosted version of
Rampguard should need only new adapters. Both require that `core` never touches files, environment variables, HTTP or
GitHub.

## Decision

`core` depends only on the Kotlin standard library, kotlinx (coroutines and serialization) and the YAML parser kaml.

The Gradle task `checkCoreDependencies` in `core/build.gradle.kts` resolves the runtime classpath of `core` and fails
the build if any artifact falls outside an explicit allow-list of groups. The allow-list includes kaml's transitive
dependencies (`snakeyaml-engine-kmp`, `okio`, `urlencoder`), which only process strings.

The YAML parser lives in `core` rather than in `cli` because policy validation is domain logic that the engine's tests
need, and parsing text is not I/O. Reading the file from disk stays in `cli`.

## Consequences

- Adding a dependency to `core` is a deliberate act. The build names the offending artifact.
- The check caught one transitive artifact (`urlencoder-lib-jvm`) on its first run. Review every new entry with the
  same question: does it perform I/O?
