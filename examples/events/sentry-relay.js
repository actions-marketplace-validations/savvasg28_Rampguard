// Cloudflare Worker: Rampguard webhook -> Sentry release and deploy.
// Secrets: RAMPGUARD_WEBHOOK_SECRET, SENTRY_TOKEN (an internal integration token with project:releases).
// Variables: SENTRY_ORG, SENTRY_PROJECT, SENTRY_URL (https://sentry.io or your self-hosted URL).
//
// Sentry already knows the release from your build step, which uploads mappings under a release name. This relay adds
// a deploy to that release on every step, named after the stage it reached, so the release page lists when each stage
// began next to its crash-free rate. The release name must match what the app reports: Android SDKs default to
// "<packageName>@<versionName>+<versionCode>".
// Variable: SENTRY_ENVIRONMENT, the environment the app reports in production, usually "production".

import { verifyRampguard } from "./verify.js";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("POST only", { status: 405 });
    const doc = await verifyRampguard(request, env.RAMPGUARD_WEBHOOK_SECRET);
    if (!doc) return new Response("bad signature", { status: 401 });
    if (!["rollout.advanced", "rollout.completed", "rollout.halted"].includes(doc.type)) return new Response("ignored");

    const headers = { "Content-Type": "application/json", Authorization: `Bearer ${env.SENTRY_TOKEN}` };
    const base = `${env.SENTRY_URL}/api/0/organizations/${env.SENTRY_ORG}`;
    const stage = doc.type === "rollout.halted" ? "halted" : `${doc.rollout.toPercent}%`;

    for (const code of doc.release.versionCodes) {
      const version = `${doc.app}@${doc.release.name}+${code}`;

      // Create the release if the build step did not. Sentry returns 208 when it already exists.
      await fetch(`${base}/releases/`, {
        method: "POST",
        headers,
        body: JSON.stringify({ version, projects: [env.SENTRY_PROJECT] }),
      });

      const deploy = await fetch(`${base}/releases/${encodeURIComponent(version)}/deploys/`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          environment: env.SENTRY_ENVIRONMENT || "production",
          name: `Rollout ${stage}`,
          url: doc.workflowRun || undefined,
          dateStarted: doc.occurredAt,
          dateFinished: doc.occurredAt,
        }),
      });
      if (!deploy.ok) return new Response(`sentry ${deploy.status}`, { status: 502 });
    }
    return new Response("ok");
  },
};

// The release page then lists "Rollout 5%", "Rollout 20%", ... with timestamps under one environment. If a step is
// followed by a drop in crash-free sessions, the Sentry issue alert in examples/halt/sentry-relay.js is the way back:
// it halts the rollout.
