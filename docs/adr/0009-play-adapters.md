# 0009: The Play adapter calls the REST API directly and edits the raw track

Date: 2026-10-20. Status: accepted. The response Play gives to a concurrent commit is still to be confirmed with a
write-capable account.

## Context

Rampguard reads and writes the production track through the Android Publisher API v3. Google publishes a generated
Java client for it. Recorded responses from a real app showed several details that the API reference does not state.

## Decisions

### Transport

The adapter calls the REST API with the JDK `HttpClient` and kotlinx.serialization instead of the generated client.
The only Google library is `google-auth-library-oauth2-http`, for access tokens. It supports service-account keys and
Workload Identity Federation, so the GitHub Action can run without a long-lived key.

This choice lets contract tests replay recorded JSON unchanged, gives every request an explicit timeout, prevents
hidden retries, and keeps the jar small. A root Gradle task fails the build if any module other than `adapters/play`
imports `com.google.*`.

### Facts about the API that shaped the wire types

- `applications.tracks.releases.list` returns at most 20 summaries. Their `releaseLifecycleState` values carry the
  prefix `RELEASE_LIFECYCLE_STATE_`. The adapter strips the prefix and maps unknown values to `UNSPECIFIED`.
- `activeArtifacts[].versionCode` is a JSON number, while `TrackRelease.versionCodes` are strings.
- `edits.tracks.get` reports `status` as `draft`, `inProgress`, `halted` or `completed`, and reports `userFraction`
  only for in-progress releases.
- A newly published staged release and the previous release are both `PUBLISHED`. The track status tells them apart.
- Play rejects the `changesNotSentForReview` parameter when the app sends changes for review automatically. This is
  independent of managed publishing. The adapter never sends the parameter.

### Writes edit the raw track JSON

`edits.tracks.update` replaces the whole track. A typed round trip would drop fields that Rampguard does not model,
such as release notes, country targeting and in-app update priority. The adapter therefore reads the track inside the
edit as JSON and changes only `status` and `userFraction` of the one release whose version codes match. It refuses to
write when the release is missing or not in progress. It does nothing when the write is already in place, so it never
lowers a fraction and never resumes a halted release.

### Edit conflicts

On `409` or `ABORTED` from `tracks.update` or `commit`, the adapter discards the edit, opens a new one, and re-reads
the track before writing again. If the re-read shows the target state, the adapter stops. A concurrent commit therefore
never causes a double advance. After three failed attempts the adapter throws `EditConflictException`.

A commit that times out or loses its connection is never retried. The adapter throws `WriteOutcomeUnknownException`,
and the next run re-reads the track.

### Permissions

With view-only Play Console permissions, the service account could read everything, and Play accepted no-op writes
inside edits: an empty commit and an unchanged track update both returned 200. Only a committed real change exercises
release permissions. Rampguard therefore verifies read-only access through the Console's permission settings, and the
dry-run guarantee rests on the runner and `GuardedPlayGateway` never writing, not on Play refusing.

## Consequences

- The contract fixtures in `adapters/play/src/test/resources/contract/` are synthetic copies of recorded shapes. A
  change in Play's responses shows up first in the live smoke test, which runs only when `RAMPGUARD_LIVE_PACKAGE` is
  set, never in CI.
- When a write-capable account is available, record Play's real conflict response and adjust `isEditConflict`.
