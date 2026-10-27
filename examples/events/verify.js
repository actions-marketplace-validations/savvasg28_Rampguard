// Verifies the X-Rampguard-Signature header: sha256=<hex HMAC-SHA256 of the raw body>.
// Returns the parsed document, or null when the signature is missing or wrong.
// Web Crypto, so it runs unchanged on Cloudflare Workers, Deno, Node 20+ and browsers.

export async function verifyRampguard(request, secret) {
  const header = request.headers.get("X-Rampguard-Signature") || "";
  const body = await request.text();
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(body));
  const expected = "sha256=" + [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
  if (!timingSafeEqual(header, expected)) return null;
  return JSON.parse(body);
}

function timingSafeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
