# Notify recipes

Rampguard sends nothing itself. It sets step outputs, and a step after it tells your tool what happened. Each recipe
below is one step to paste after the `rampguard` step in `rollout-tick.yml` or `rollout-command.yml`. Each holds its
own secret; Rampguard never sees it.

The outputs are listed in the [README](../../README.md#tell-your-tools). Start with `summary`; use the single values
or `record` when a tool needs fields of its own.

## Slack

One line per step, halt or failure. `summary` is the whole message; `toJSON` quotes it safely.

```yaml
- name: Tell Slack
  if: ${{ !cancelled() && steps.rampguard.outputs.event != 'none' }}
  uses: slackapi/slack-github-action@dcb1066f776dd043e64d0e8ba94ca15cc7e1875d # v4.0.0
  with:
    webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
    webhook-type: incoming-webhook
    payload: |
      text: ${{ toJSON(steps.rampguard.outputs.summary) }}
```

A reminder while a release waits for someone to press publish. Runs keep no memory, so this fires on every run that
sees the release.

```yaml
- name: Remind Slack of a release waiting to be published
  if: ${{ !cancelled() && steps.rampguard.outputs.ready-to-publish != '' }}
  uses: slackapi/slack-github-action@dcb1066f776dd043e64d0e8ba94ca15cc7e1875d # v4.0.0
  with:
    webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
    webhook-type: incoming-webhook
    payload: |
      text: "${{ steps.rampguard.outputs.ready-to-publish }} is approved and waiting for someone to press publish in the Play Console."
```

## Sentry

A deploy per step on the release, so Release Health shows when each stage began. The release name must match what the
app reports; Android SDKs default to `<package>@<versionName>+<versionCode>`.

```yaml
- name: Record the step in Sentry
  if: ${{ steps.rampguard.outputs.event == 'advanced' || steps.rampguard.outputs.event == 'completed' }}
  uses: getsentry/action-release@e6703d0b00208e0b3687ba660d18ab5af7892821 # v3.7.2
  env:
    SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}
    SENTRY_ORG: acme
    SENTRY_PROJECT: android
  with:
    release: com.example.app@${{ steps.rampguard.outputs.release-name }}+${{ steps.rampguard.outputs.version-codes }}
    environment: production
    set_commits: skip
```

## Datadog

An event on the event stream, tagged for dashboards and monitors.

```yaml
- name: Record the step in Datadog
  if: ${{ steps.rampguard.outputs.event != 'none' }}
  uses: masci/datadog@aadd4fb98a29438db71fae50c698cad8bc782ad3 # v2.0.7
  with:
    api-key: ${{ secrets.DATADOG_API_KEY }}
    events: |
      - title: "Rollout ${{ steps.rampguard.outputs.release-name }}: ${{ steps.rampguard.outputs.event }}"
        text: "${{ steps.rampguard.outputs.from-percent }}% -> ${{ steps.rampguard.outputs.to-percent }}% ${{ steps.rampguard.outputs.reason }}"
        alert_type: ${{ steps.rampguard.outputs.event == 'failed' && 'error' || 'info' }}
        tags:
          - "app:com.example.app"
          - "release:${{ steps.rampguard.outputs.release-name }}"
          - "event:${{ steps.rampguard.outputs.event }}"
```

## Grafana

An annotation, so dashboards show each stage as a vertical line over crash rate or latency.

```yaml
- name: Annotate Grafana
  if: ${{ steps.rampguard.outputs.event != 'none' }}
  env:
    GRAFANA_URL: https://acme.grafana.net
    GRAFANA_TOKEN: ${{ secrets.GRAFANA_TOKEN }}
    TEXT: "${{ steps.rampguard.outputs.release-name }}: ${{ steps.rampguard.outputs.event }} ${{ steps.rampguard.outputs.from-percent }}% -> ${{ steps.rampguard.outputs.to-percent }}%"
    TAGS: '["rampguard", "com.example.app", "${{ steps.rampguard.outputs.event }}"]'
  run: |
    curl -sSf -X POST "$GRAFANA_URL/api/annotations" \
      -H "Authorization: Bearer $GRAFANA_TOKEN" -H "Content-Type: application/json" \
      -d "$(jq -n --arg text "$TEXT" --argjson tags "$TAGS" '{text: $text, tags: $tags}')"
```
