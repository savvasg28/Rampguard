# 0013: Report through step outputs; notifying is the workflow's job

Date: 2026-10-27. Status: accepted.

## Context

Rampguard has no view of app health (ADR 0010), but the tools that do, such as Slack, Sentry, Datadog and Grafana,
cannot show *when* a rollout stage changed unless something tells them. Three designs were tried in one week: a Slack
notifier in the jar, then a generic signed webhook with relay scripts, then a `notify` list of destinations with
`${NAME}` secrets. Each was harder to explain than the last, and each put a vendor's shape or a credential convention
inside Rampguard.

The established GitHub Actions do not do this. `upload-google-play`, `release-please-action` and `action-gh-release`
expose outputs and send nothing. `slackapi/slack-github-action`, `getsentry/action-release` and `masci/datadog` each
take the tool's own secret and accept `${{ steps.x.outputs.y }}` in their inputs. Workflows compose them with `if:`.
Rampguard's halt side already works this way: it is a `repository_dispatch`, not a built-in Sentry client.

## Decision

Rampguard talks to nothing but Play. A run produces one decision record, and the CLI renders it three ways: text on
stdout, the same text in the job summary, and GitHub step outputs. The action declares the outputs: `event`,
`release-name`, `version-codes`, `from-percent`, `to-percent`, `reason`, `source`, `ready-to-publish`, `rejected`.
Every output is always set, empty when it does not apply. The names are a contract: add, never rename.

Notifying is the workflow's job. `examples/notify/` carries working steps for Slack, Sentry, Datadog and Grafana,
each gated on the outputs and each holding its own secret. The example workflows themselves contain only the
Rampguard step, so an installer starts from the smallest working file and adds what they use. The `notify` key, the `Notifier` port and the notifier
adapters are removed.

## Consequences

- The configuration file holds `app` and `stages`. Rampguard holds one secret, the Play key.
- Adding a destination is a workflow step with that tool's action, not a Rampguard change or release.
- `event` reports what was done, so a dry run reports `none` and a gated Slack step stays quiet. The log and the
  summary still say what would have happened.
- A release waiting for a person appears in `ready-to-publish` on every run that sees it (ADR 0011). Whether to remind
  people daily is a workflow decision.
- The `v1` jar still accepts `notify.slackWebhookEnv` and posts to Slack itself; the next release rejects `notify`
  with a pointer to this record.

## Amendment, 2026-11-10: three layers of output

The flat outputs made the common case expensive. A one-line Slack message needed five of them, and printed `% -> %`
on a halt or a failure. Two outputs are added, so the outputs form layers in the sense of Jake Wharton's
[slope-intercept library design](https://jakewharton.com/slope-intercept-library-design/): a cheap layer for most
uses, and lower layers for more control.

- `summary`: one line saying what the run did, including what a dry run would have done. A chat step posts it as is.
- The existing flat outputs, unchanged, for `if:` conditions and tool inputs.
- `record`: the whole run as one JSON object with the same names and typed values, plus `app`, `command`, `at`,
  `dry-run`, `decision` and `notes`. A new value goes here first, so the flat outputs grow only when workflows need a
  value in an `if:`.

The names inside `record` are under the same contract as the outputs: add, never rename. Both outputs are additive,
so `@v1` keeps working.
