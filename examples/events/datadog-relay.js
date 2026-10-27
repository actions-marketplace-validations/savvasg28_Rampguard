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
    const titles = {
      "rollout.advanced": () => `Rollout ${version} advanced ${doc.rollout.fromPercent}% -> ${doc.rollout.toPercent}%`,
      "rollout.completed": () => `Rollout ${version} completed`,
      "rollout.halted": () => `Rollout ${version} halted: ${doc.halt.reason}`,
      "release.readyToPublish": () => `Release ${version} is approved and waiting to be published`,
      "release.rejected": () => `Release ${version} was rejected by Play review`,
      "run.failed": () => `Rampguard could not read or step the rollout`,
    };
    const alertTypes = { "rollout.halted": "error", "release.rejected": "error", "run.failed": "error", "release.readyToPublish": "warning" };

    const body = {
      title: titles[doc.type] ? titles[doc.type]() : `Rampguard ${doc.type}`,
      text: doc.failure ? doc.failure.message : doc.workflowRun || "",
      alert_type: alertTypes[doc.type] || "info",
      source_type_name: "rampguard",
      date_happened: Math.floor(Date.parse(doc.occurredAt) / 1000),
      tags: [
        `app:${doc.app}`,
        `type:${doc.type}`,
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

// The tags let a dashboard overlay rollout steps on crash-free rate (type:rollout.advanced) and let a monitor alert on
// type:rollout.halted or type:run.failed. A monitor on type:release.readyToPublish, after a quiet period, tells you that
// a release has been sitting approved while nobody pressed publish.
