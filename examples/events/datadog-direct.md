# Datadog, without a relay

Rampguard can post straight to Datadog because its webhook sends custom headers, so the `DD-API-KEY` header travels
with the request. The Events API wants its own body shape, which Rampguard does not produce, so the direct route goes to
log intake instead. Log intake accepts any JSON object and keeps every field as an attribute. For an entry on the event
stream, with a title and alert type, use [`datadog-relay.js`](datadog-relay.js).

## Configuration

```yaml
notify:
  webhook:
    urlEnv: RAMPGUARD_WEBHOOK_URL
    headers:
      DD-API-KEY: env:DD_API_KEY
      ddsource: rampguard
      ddtags: env:DD_TAGS
```

Workflow environment:

```yaml
env:
  RAMPGUARD_WEBHOOK_URL: https://http-intake.logs.datadoghq.com/api/v2/logs   # or your site's intake host
  DD_API_KEY: ${{ secrets.DD_API_KEY }}
  DD_TAGS: team:mobile,service:android-app
```

Use an API key created for this purpose. Log intake needs only the key.

## What you get

One log per event with `@event`, `@app`, `@release.name`, `@release.versionCodes`, `@from`, `@to`, `@reason`,
`@source` and `@run` as attributes. Useful searches and monitors:

| Need | Query |
|---|---|
| Rollout history of one version | `source:rampguard @release.name:5.25.0` |
| Alert when a rollout halts or Rampguard fails | Log monitor on `source:rampguard (@event:halted OR @event:error)` |
| A release approved but not published for a day | Log monitor on `source:rampguard @event:ready_to_publish`, threshold over 24 hours |
| Steps as a timeline next to crash-free rate | Add a log stream widget with `source:rampguard @event:advanced` under the crash chart |
