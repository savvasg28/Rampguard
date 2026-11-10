// Relay a Sentry issue-alert webhook to a Rampguard halt.
//
// Sentry webhooks cannot send an Authorization header, so this Cloudflare Worker receives the alert, checks its
// signature, extracts the version code from the release name, and sends the repository_dispatch to GitHub.
//
// Deploy with wrangler and set these secrets:
//   SENTRY_CLIENT_SECRET   the internal integration's client secret, used to verify `sentry-hook-signature`
//   GITHUB_TOKEN           fine-grained token with Contents: write on the app repository
//   GITHUB_REPO            owner/repo of the app repository
//   VERSION_CODE_PATTERN   optional regex with one capture group; default takes the digits after "+" in the release
//                          name, which matches Sentry's recommended "package@version+buildNumber" format
//
// In Sentry, create an internal integration with the webhook URL of this Worker and the "issue" alert webhook enabled,
// then add it as an action on the alert rules that should halt a rollout.

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("POST only", { status: 405 });
    const body = await request.text();

    const expected = await hmacHex(env.SENTRY_CLIENT_SECRET, body);
    if (request.headers.get("sentry-hook-signature") !== expected) {
      return new Response("bad signature", { status: 401 });
    }

    const alert = JSON.parse(body);
    const release = alert?.data?.event?.release ?? "";
    const pattern = new RegExp(env.VERSION_CODE_PATTERN ?? "\\+(\\d+)$");
    const match = release.match(pattern);
    if (!match) return new Response(`no version code in release "${release}"`, { status: 202 });

    const title = alert?.data?.event?.title ?? alert?.data?.issue?.title ?? "Sentry alert";
    const response = await fetch(`https://api.github.com/repos/${env.GITHUB_REPO}/dispatches`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.GITHUB_TOKEN}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "rampguard-sentry-relay",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        event_type: "rollout-halt",
        client_payload: { source: "sentry", reason: title.slice(0, 200), "version-code": Number(match[1]) },
      }),
    });
    return new Response(response.ok ? "halt dispatched" : `github said ${response.status}`, {
      status: response.ok ? 200 : 502,
    });
  },
};

async function hmacHex(secret, text) {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(text));
  return [...new Uint8Array(signature)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
