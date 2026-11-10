# Datadog monitor to halt

Datadog webhooks can send custom headers, so a monitor can call GitHub directly.

## 1. Create the webhook

In Datadog, open **Integrations → Webhooks** and add one:

| Field | Value |
|---|---|
| Name | `rampguard-halt` |
| URL | `https://api.github.com/repos/OWNER/REPO/dispatches` |
| Payload | the JSON below |
| Custom headers | `{"Authorization": "Bearer <token>", "Accept": "application/vnd.github+json"}` |

Payload:

```json
{
  "event_type": "rollout-halt",
  "client_payload": {
    "source": "datadog",
    "reason": "$ALERT_TITLE",
    "version-code": "$ALERT_SCOPE"
  }
}
```

`$ALERT_SCOPE` is the group the monitor alerted on, for example `version_code:101`. The action keeps only the digits,
so this becomes version code 101. For that to work, the monitor must group by a tag that carries the version code.

## 2. Create the monitor

Group the crash or ANR metric by the tag that holds the version code (for example `version_code`), set the threshold,
and in the notification message mention the webhook:

```
Crash rate above threshold for {{version_code.name}}. @webhook-rampguard-halt
```

## 3. Test it

Use the monitor's **Test Notifications** button with a group that matches a version code that is not live. The
command workflow should run and report `versionCode N is not the active rollout`, which proves the wiring without
halting anything.
