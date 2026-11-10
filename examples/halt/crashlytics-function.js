// Halt the rollout when Crashlytics raises a velocity alert for the live release.
//
// Crashlytics has no generic webhook, but Firebase Functions v2 exposes its alerts as triggers. This function runs on
// a velocity alert (a crash affecting a rising share of sessions), reads the version code out of the alert's
// `issue.appVersion` ("versionName (versionCode)") and sends the repository_dispatch that halts the rollout.
//
// Deploy from a Firebase Functions project (Node 20, firebase-functions v5 or later):
//   npm install firebase-functions
//   firebase functions:secrets:set GITHUB_TOKEN        # fine-grained token, Contents: write on the app repository
//   firebase deploy --only functions
// Set GITHUB_REPO below, or as an environment variable in .env.
//
// Several issues can raise velocity alerts for the same build. Only the first halt changes anything; the rest are
// no-ops because the release is already halted.

const { onVelocityAlertPublished } = require("firebase-functions/v2/alerts/crashlytics");
const { defineSecret } = require("firebase-functions/params");

const githubToken = defineSecret("GITHUB_TOKEN");
const GITHUB_REPO = process.env.GITHUB_REPO ?? "OWNER/REPO";

exports.rampguardHaltOnVelocityAlert = onVelocityAlertPublished({ secrets: [githubToken] }, async (event) => {
  const { issue, crashPercentage } = event.data.payload;
  const code = issue.appVersion?.match(/\((\d+)\)/)?.[1];
  if (!code) {
    console.log(`no version code in appVersion "${issue.appVersion}"; nothing to halt`);
    return;
  }

  const reason = `Crashlytics velocity alert: ${issue.title} (${crashPercentage.toFixed(1)}% of sessions)`;
  const response = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/dispatches`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${githubToken.value()}`,
      Accept: "application/vnd.github+json",
      "User-Agent": "rampguard-crashlytics-relay",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      event_type: "rollout-halt",
      client_payload: { source: "crashlytics", reason: reason.slice(0, 200), "version-code": Number(code) },
    }),
  });

  if (!response.ok) throw new Error(`GitHub dispatch failed: ${response.status} ${await response.text()}`);
  console.log(`halt dispatched for version code ${code}`);
});
