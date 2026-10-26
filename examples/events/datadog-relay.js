// Cloudflare Worker: Rampguard webhook -> Datadog event.
// Secrets: RAMPGUARD_WEBHOOK_SECRET (same value Rampguard signs with), DD_API_KEY.
// Variable: DD_SITE, such as datadoghq.com or datadoghq.eu.

import { verifyRampguard } from "./verify.js";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("POST only", { status: 405 });
    const doc = await verifyRampguard(request, env.RAMPGUARD_WEBHOOK_SECRET);
    if (!doc) return new Response("bad signature", { status: 401 });

    const version = doc.release ? doc.release.name : "unknown";
    const pct = (f) => Math.round(f * 100) + "%";
    const titles = {
      advanced: `Rollout ${version} advanced ${pct(doc.from)} -> ${pct(doc.to)}`,
      completed: `Rollout ${version} completed`,
      halted: `Rollout ${version} halted: ${doc.reason}`,
      ready_to_publish: `Release ${version} is approved and waiting to be published`,
      rejected: `Release ${version} was rejected by Play review`,
      error: `Rampguard could not read or step the rollout`,
    };
    const alertType = { halted: "error", rejected: "error", error: "error", ready_to_publish: "warning" }[doc.event] || "info";

    const body = {
      title: titles[doc.event] || `Rampguard ${doc.event}`,
      text: doc.message || doc.run || "",
      alert_type: alertType,
      source_type_name: "rampguard",
      date_happened: Math.floor(Date.parse(doc.at) / 1000),
      tags: [
        `app:${doc.app}`,
        `event:${doc.event}`,
        ...(doc.release ? [`version:${version}`, ...doc.release.versionCodes.map((c) => `version_code:${c}`)] : []),
      ],
    };

    const res = await fetch(`https://api.${env.DD_SITE}/api/v1/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "DD-API-KEY": env.DD_API_KEY },
      body: JSON.stringify(body),
    });
    return new Response(res.ok ? "ok" : `datadog ${res.status}`, { status: res.ok ? 200 : 502 });
  },
};

// The tags let a dashboard overlay rollout steps on crash-free rate (event:advanced) and let a monitor alert on
// event:halted or event:error. A monitor on event:ready_to_publish, after a quiet period, tells you that a release has
// been sitting approved while nobody pressed publish.
