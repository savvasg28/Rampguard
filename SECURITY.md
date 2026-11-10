# Security

## Report a vulnerability

Report it privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**, or go
to https://github.com/savvasg28/Rampguard/security/advisories/new. Include a description, the affected version and
steps to reproduce. Do not open a public issue for a security problem. You will get an acknowledgement within three working days and a fix or a mitigation
plan within thirty.

## What Rampguard can touch

Rampguard runs inside your GitHub Actions workflow with one secret:

| Secret | What it grants | How to limit it |
|---|---|---|
| Play service-account key | Reading the production track; changing the rollout fraction, halting and completing the live release. | Give the service account access to this app only, with *View app information* and *Release to production*. Never grant it account-level permissions. |

The action writes the key to a mode-600 file in the runner's temporary directory for the duration of the run and
deletes it afterwards. The key never appears in logs, the job summary, the step outputs, exceptions or test output.

Rampguard talks to nothing but Play. Notifying Slack, Sentry, Datadog or Grafana is done by the steps after it in your
workflow, with those tools' own actions and secrets, so Rampguard never holds them.

## Who can halt a rollout

Anyone whose token can send `repository_dispatch` to the app repository can halt the live release. That is the trust
boundary. Give each sender a fine-grained token limited to that repository, and revoke it when the sender goes away.
Rampguard treats `reason` and `source` as untrusted text: it removes control characters, collapses whitespace and
truncates them, and never interpolates them into a shell command.

## Untrusted text in the outputs

The `reason` and `source` outputs, and the `summary` and `record` outputs that contain them, carry text chosen by
whoever sent the halt. Rampguard's sanitising removes control characters but leaves quotes, `$` and backticks, so
the text is not safe to paste into a shell script. In the steps after Rampguard:

- Pass these outputs to a `run:` script through `env:` and use `"$VARIABLE"`. Never write
  `${{ steps.rampguard.outputs.summary }}` inside `run:`.
- Inside a `with:` value that another action parses as JSON or YAML, wrap them in `toJSON(...)`, as the Slack and
  Datadog recipes in `examples/notify/` do.

`event`, `version-codes` and the percentages are produced by Rampguard and contain only known words, digits, commas
and dots. `release-name`, `ready-to-publish` and `rejected` come from Play and hold whatever the person who created
the release typed; treat them like `reason`.

## What Rampguard never does

- Write to Play during a dry run. The runner skips the write and a second guard refuses it.
- Write to a halted or completed release.
- Resume a halted release. That is a human decision in the Play Console.
- Run anything but the released jar whose SHA-256 is pinned in `action.yml`.

## Supported versions

Only the latest release receives fixes.
