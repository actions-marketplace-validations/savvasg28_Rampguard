// Cloudflare Worker: Rampguard webhook -> Grafana annotation.
// Secrets: RAMPGUARD_WEBHOOK_SECRET, GRAFANA_TOKEN (a service-account token with the Editor role, or
// annotations:write).
// Variable: GRAFANA_URL, such as https://acme.grafana.net.
//
// The annotation is organisation-wide, tagged with the app and version, so any panel whose annotation query selects
// the tag "rampguard" shows each rollout step as a vertical line over crash rate, ANR rate or API latency. Halts are
// tagged "halted" so a dashboard can colour them differently.

import { verifyRampguard } from "./verify.js";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("POST only", { status: 405 });
    const doc = await verifyRampguard(request, env.RAMPGUARD_WEBHOOK_SECRET);
    if (!doc) return new Response("bad signature", { status: 401 });

    const version = doc.release ? doc.release.name : "unknown";
    const pct = (f) => Math.round(f * 100) + "%";
    const text = {
      advanced: `${version}: ${pct(doc.from)} -> ${pct(doc.to)}`,
      completed: `${version}: completed`,
      halted: `${version}: halted (${doc.reason})`,
      ready_to_publish: `${version}: approved, waiting to be published`,
      rejected: `${version}: rejected by Play review`,
      error: `Rampguard error: ${doc.message}`,
    }[doc.event] || doc.event;

    const res = await fetch(`${env.GRAFANA_URL}/api/annotations`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${env.GRAFANA_TOKEN}` },
      body: JSON.stringify({
        time: Date.parse(doc.at),
        tags: ["rampguard", doc.event, `app:${doc.app}`, `version:${version}`],
        text: doc.run ? `${text} (${doc.run})` : text,
      }),
    });
    return new Response(res.ok ? "ok" : `grafana ${res.status}`, { status: res.ok ? 200 : 502 });
  },
};

// Dashboard setup: Dashboard settings -> Annotations -> New, data source "Grafana", filter by tags, tag "rampguard".
// Add "app:com.example.app" when several apps share the Grafana instance.
