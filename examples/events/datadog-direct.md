# Datadog, without a relay

Rampguard's webhook carries custom headers, so a `DD-API-KEY` header can travel with the request and Datadog can receive
the document directly. The Events API wants its own body shape, which Rampguard does not produce, so the direct route
goes to log intake, which accepts any JSON object and keeps every field as a log attribute. For an entry on the event
stream, with a title and an alert type, use [`datadog-relay.js`](datadog-relay.js).

## Configuration

```yaml
notify:
  - webhook:
      url: https://http-intake.logs.datadoghq.com/api/v2/logs?ddsource=rampguard&ddtags=team:mobile
      headers:
        DD-API-KEY: ${DD_API_KEY}
```

Source and tags are query parameters of the intake URL. Replace the host with your site's intake host, such as
`http-intake.logs.datadoghq.eu`. The workflow passes the key as an environment variable:

```yaml
env:
  DD_API_KEY: ${{ secrets.DD_API_KEY }}
```

Use an API key created for this purpose. Log intake needs only the key.

## What you get

One log per event with `@type`, `@app`, `@release.name`, `@release.versionCodes`, `@rollout.fromPercent`,
`@rollout.toPercent`, `@halt.reason`, `@halt.source`, `@failure.message` and `@workflowRun` as attributes.

| Need | Query |
|---|---|
| Rollout history of one version | `source:rampguard @release.name:5.25.0` |
| Alert when a rollout halts or Rampguard fails | Log monitor on `source:rampguard (@type:rollout.halted OR @type:run.failed)` |
| A release approved but not published for a day | Log monitor on `source:rampguard @type:release.readyToPublish`, threshold over 24 hours |
| Steps as a timeline next to crash-free rate | A log stream widget with `source:rampguard @type:rollout.advanced` under the crash chart |
