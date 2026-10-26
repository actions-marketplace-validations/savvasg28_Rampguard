# Rampguard

Rampguard is a GitHub Action that advances a Google Play staged rollout one stage per scheduled run, and halts the
rollout when an alerting tool or a person asks. It needs one YAML file in your app repository and two secrets. It runs
no server and keeps no state.

This README is for the engineer who installs Rampguard in an Android app repository. It assumes you already upload
and submit releases through the Play Console or CI, and that your repository uses GitHub Actions.

> **Status: early.** Listed on the GitHub Marketplace as
> [Rampguard Play Rollout](https://github.com/marketplace/actions/rampguard-play-rollout); `savvasg28/Rampguard@v1`
> resolves to the current release.

## Terms

| Term | Meaning |
|---|---|
| **release** | A version on the Play production track, identified by its version codes. |
| **stage** | A rollout percentage from your `stages` list, for example 5%. |
| **step** | Moving the live release from its current stage to the next one. 100% completes the release. |
| **run** | One execution of the tick workflow: scheduled, or triggered by hand. |

## How it works

Each run reads the production track and does one of the following:

| The run sees | The run does |
|---|---|
| A release in progress at a stage | Steps it to the next stage. At the last stage, completes it. |
| A release in progress between two stages | Treats it as the lower stage and steps to the stage above. |
| A halted or completed release | Nothing. A person resumes a halted release in the Play Console. |
| A release waiting to be published, or rejected | Nothing, but reports it. |
| An unreadable Play API | Nothing, and fails the job. |

The cron expression is the schedule. `0 9 * * 1-5` steps every weekday morning: 1% on Monday becomes 5% on Tuesday,
20% on Wednesday, and so on. Weekends and freezes are whatever the cron does not cover.

Rampguard does not judge release health. Play vitals arrive days late and are too coarse to gate a rollout
([ADR 0010](docs/adr/0010-scheduled-rollouts-with-external-brakes.md)). The decision to stop belongs to the tools you
already alert from; they tell Rampguard to halt.

## Install

1. Add two repository secrets:
   - `PLAY_SERVICE_ACCOUNT_JSON`: the JSON key of a Play service account that can see this app only, with *View app
     information* and *Release to production* permissions.
   - `SLACK_WEBHOOK_URL`: optional. An incoming-webhook URL for notifications.
   - `RAMPGUARD_WEBHOOK_URL`: optional. A URL that receives one JSON document per event, for your own tooling.
2. Copy [`examples/workflows/rollout-tick.yml`](examples/workflows/rollout-tick.yml) and
   [`examples/workflows/rollout-command.yml`](examples/workflows/rollout-command.yml) into `.github/workflows/`.
3. In both workflow files, set the concurrency group to your package name.
4. Add `.github/rampguard.yml` (see [Configuration](#configuration)).
5. From the Actions tab, run the tick workflow once with **dry-run** selected, then read the job summary. The run
   reports what it would do and writes nothing.

From the next scheduled run on, Rampguard steps the live release.

To step before the schedule, run the tick workflow by hand without dry-run. To stop Rampguard entirely, disable the
tick workflow in the Actions tab.

## Configuration

`.github/rampguard.yml`:

```yaml
app: com.example.app
stages: [1, 5, 20, 50, 100]
notify:
  slackWebhookEnv: SLACK_WEBHOOK_URL
  webhook:
    urlEnv: RAMPGUARD_WEBHOOK_URL
    secretEnv: RAMPGUARD_WEBHOOK_SECRET
```

| Key | Required | Meaning |
|---|---|---|
| `app` | yes | The application package name. |
| `stages` | yes | Rollout percentages, strictly increasing, ending in 100. You set the first stage when you publish the release; Rampguard owns every stage after it. |
| `notify.slackWebhookEnv` | no | The name of the environment variable that holds the Slack webhook URL. |
| `notify.webhook.urlEnv` | no | The name of the environment variable that holds a URL to `POST` one JSON document per event to. |
| `notify.webhook.secretEnv` | no | The name of the environment variable that holds a shared secret. When set, each request carries `X-Rampguard-Signature: sha256=<HMAC-SHA256 of the body>`. |
| `notify.webhook.headersEnv` | no | Extra request headers, header name to the name of the environment variable that holds the value. Values are never written in the file, so an API key stays a secret. |

Without a `notify` block, events go to the job log and the job summary only. Unknown keys are rejected, so a typo fails
the job before any API call.

The webhook document looks like this. The field names and the `event` values are a contract: fields are added, never
renamed.

```json
{"event": "advanced", "app": "com.example.app",
 "at": "2026-10-28T09:17:41Z", "run": "https://github.com/acme/app/actions/runs/123",
 "release": {"name": "5.25.0", "versionCodes": [52500]},
 "from": 0.05, "to": 0.2, "reason": null, "source": null, "message": null}
```

Recipes that relay these events to Datadog, Sentry and Grafana are in [`examples/events/`](examples/events/README.md).

Because a run steps whatever release is live, publish a new release after the morning run. A release published minutes
before the run gets its first step minutes later.

## Halt a rollout

Send a `repository_dispatch` event to the app repository:

```sh
curl -X POST https://api.github.com/repos/OWNER/REPO/dispatches \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"event_type":"rollout-halt","client_payload":{"source":"sentry","reason":"crash spike","versionCode":101}}'
```

The command workflow halts the release in Play if `versionCode` belongs to the live release. A second halt, or a halt
for another version code, does nothing. A person can also halt from the Actions tab.

Anyone whose token can dispatch to the repository can halt the rollout. Use a fine-grained token per sender.
Resuming a halted release is a human decision in the Play Console.

Recipes for Datadog, Sentry, Crashlytics and the shell are in [`examples/halt/`](examples/halt/README.md).

## Release Rampguard

This section is for maintainers.

In the Actions tab, run the Release workflow with the version, for example `0.2.0`. It runs the checks, builds the
jar, pins the jar's SHA-256 in `action.yml`, commits that pin to `main`, moves the `v1` tag to the new commit, and
uploads the jar and its checksum to the single `v1` release. Installers always use `@v1`.

The action refuses to run a jar whose checksum does not match the pin.

## Run locally

You can run every command from a checkout without touching any workflow. Each command reads Play and, except for a
real `tick` or `halt`, writes nothing.

```sh
export GOOGLE_APPLICATION_CREDENTIALS=~/keys/play-service-account.json
bin/rampguard status --config ~/rampguard-myapp.yml
bin/rampguard tick --dry-run --config ~/rampguard-myapp.yml
```

`bin/rampguard` builds the launcher on first use. To install it elsewhere, run `./gradlew :cli:installDist` and copy
`cli/build/install/rampguard/`.

## Develop

```sh
./gradlew check                                                  # build, tests, lint, dependency rules, coverage gate
./gradlew ktlintFormat                                           # format before committing
./gradlew :cli:run --args="validate --config examples/rampguard.yml"
```

Design: [`docs/design.md`](docs/design.md). Conventions: [`CLAUDE.md`](CLAUDE.md).

## License

Apache-2.0. See [`LICENSE`](LICENSE).
