# Rampguard

Rampguard is a GitHub Action that advances a Google Play staged rollout one stage per scheduled run, and halts the
rollout when an alerting tool or a person asks. It needs one YAML file in your app repository and one secret. It runs
no server and keeps no state. It reports what it did as step outputs, so the steps after it in your workflow can tell
Slack, Sentry, Datadog or anything else.

This README is for the engineer who installs Rampguard in an Android app repository. It assumes you already upload
and submit releases through the Play Console or CI, and that your repository uses GitHub Actions.

> **Status: early.** Listed on the GitHub Marketplace as
> [Rampguard Play Rollout](https://github.com/marketplace/actions/rampguard-play-rollout). `savvasg28/Rampguard@v1`
> follows the latest release; `@v1.0.0` and later pin one.

## Terms

| Term | Meaning |
|---|---|
| **release** | A version on the Play production track, identified by its version codes. |
| **stage** | A rollout percentage from your `stages` list, for example 5%. |
| **step** | Moving the live release from its current stage to the next one. 100% completes the release. |
| **run** | One execution of the tick workflow: scheduled, or triggered by hand. |

## How it works

Each run reads the production track and does one of the following:

| The run sees | The run does |
|---|---|
| A release in progress at a stage | Steps it to the next stage. At the last stage, completes it. |
| A release in progress between two stages | Treats it as the lower stage and steps to the stage above. |
| A halted or completed release | Nothing. A person resumes a halted release in the Play Console. |
| A release waiting to be published, or rejected | Nothing, but reports it. |
| An unreadable Play API | Nothing, and fails the job. |

The cron expression is the schedule. `0 9 * * 1-5` steps every weekday morning: 1% on Monday becomes 5% on Tuesday,
20% on Wednesday, and so on. Weekends and freezes are whatever the cron does not cover.

Rampguard does not judge release health. Play vitals arrive days late and are too coarse to gate a rollout
([ADR 0010](docs/adr/0010-scheduled-rollouts-with-external-brakes.md)). The decision to stop belongs to the tools you
already alert from; they tell Rampguard to halt.

## Install

1. Add the repository secret `PLAY_SERVICE_ACCOUNT_JSON`: the JSON key of a Play service account that can see this app
   only, with *View app information* and *Release to production* permissions.
2. Copy [`examples/workflows/rollout-tick.yml`](examples/workflows/rollout-tick.yml) and
   [`examples/workflows/rollout-command.yml`](examples/workflows/rollout-command.yml) into `.github/workflows/`.
3. Add `.github/rampguard.yml` (see [Configuration](#configuration)).
4. From the Actions tab, run the tick workflow once with **dry-run** selected, then read the job summary. The run
   reports what it would do and writes nothing.

From the next scheduled run on, Rampguard steps the live release.

To step before the schedule, run the tick workflow by hand without dry-run. To stop Rampguard entirely, disable the
tick workflow in the Actions tab.

## Configuration

`.github/rampguard.yml`:

```yaml
app: com.example.app
stages: [1, 5, 20, 50, 100]
```

| Key | Required | Meaning |
|---|---|---|
| `app` | yes | The application package name. |
| `stages` | yes | Rollout percentages, strictly increasing, ending in 100. You set the first stage when you publish the release; Rampguard owns every stage after it. |

Unknown keys are rejected, so a typo fails the job before any API call.

Because a run steps whatever release is live, publish a new release after the morning run. A release published minutes
before the run gets its first step minutes later.

## Tell your tools

Rampguard writes what happened to the job log, the job summary and the step outputs. It sends nothing anywhere itself.
A step after it reads the outputs and calls the tool's own action:

```yaml
- id: rampguard
  uses: savvasg28/Rampguard@v1
  with:
    command: tick
    play-credentials: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}

- if: steps.rampguard.outputs.event != 'none'
  uses: slackapi/slack-github-action@v4
  with:
    webhook: ${{ secrets.SLACK_WEBHOOK_URL }}
    webhook-type: incoming-webhook
    payload: |
      text: ${{ toJSON(steps.rampguard.outputs.summary) }}
```

The outputs come in three layers. Use the one that fits and drop to the next only when you need more.

| Layer | Output | Use it for |
|---|---|---|
| Message | `summary` | One line to post as it is, for example `com.example.app 5.25.0 (101) advanced 5% → 20%`. |
| Values | the outputs below | `if:` conditions and the inputs of other actions. |
| Everything | `record` | The whole run as JSON, read with `fromJSON(steps.rampguard.outputs.record)`. |

| Output | Value |
|---|---|
| `event` | What this run did to the live release: `advanced`, `completed`, `halted`, `failed` or `none`. A dry run reports `none`. |
| `release-name`, `version-codes` | The live release. Version codes are comma-separated. |
| `from-percent`, `to-percent` | The step taken, on `advanced` and `completed`. |
| `reason`, `source` | Why and who, on `halted`. |
| `ready-to-publish` | A release that Play review approved and a person has to publish. Reported on every run that sees it. |
| `rejected` | A release that Play review rejected. |

In `record` the same names hold typed values: `version-codes`, `ready-to-publish` and `rejected` are arrays, the
percentages are numbers, and a value that does not apply is `null`. It also carries `app`, `command`, `at`, `dry-run`,
`decision` and `notes`.

`tick` and `halt` set every output, empty when it does not apply; `status` and `validate` set none. The names, including the field names inside `record`, are a
contract: they are added, never renamed.
Steps for Slack, Sentry releases, Datadog events and Grafana annotations are in
[`examples/notify/`](examples/notify/README.md).

## Halt a rollout

Send a `repository_dispatch` event to the app repository:

```sh
curl -X POST https://api.github.com/repos/OWNER/REPO/dispatches \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"event_type":"rollout-halt","client_payload":{"source":"sentry","reason":"crash spike","version-code":101}}'
```

The command workflow halts the release in Play if `version-code` belongs to the live release. A second halt, or a halt
for another version code, does nothing. A person can also halt from the Actions tab.

Anyone whose token can dispatch to the repository can halt the rollout. Use a fine-grained token per sender.
Resuming a halted release is a human decision in the Play Console.

Recipes for Datadog, Sentry, Crashlytics and the shell are in [`examples/halt/`](examples/halt/README.md).

## Release Rampguard

This section is for maintainers.

In the Actions tab, run the Release workflow with a version such as `1.0.0` and, optionally, a line of notes. It runs
the checks, builds the jar, pins the jar's release tag and SHA-256 in `action.yml`, commits that to `main`, creates the
tag and release `v1.0.0` with the jar and its checksum attached, and moves the `v1` tag to the same commit. A released
version is never rebuilt.

Then open the new release in the browser, edit it, tick **Publish this Action to the GitHub Marketplace** and update it.
The Marketplace flag can only be set in the browser, and the listing shows the newest release that has it.

`@v1` follows every release that keeps the inputs and the configuration working. A change that breaks either is a new
major version. Installers who want an exact version use `@v1.0.0`.

The action refuses to run a jar whose checksum does not match the pin.

## Run locally

You can run every command from a checkout without touching any workflow. Each command reads Play and, except for a
real `tick` or `halt`, writes nothing.

```sh
export GOOGLE_APPLICATION_CREDENTIALS=~/keys/play-service-account.json
bin/rampguard status --config ~/rampguard-myapp.yml
bin/rampguard tick --dry-run --config ~/rampguard-myapp.yml
```

`bin/rampguard` builds the launcher on first use. To install it elsewhere, run `./gradlew :cli:installDist` and copy
`cli/build/install/rampguard/`.

## Develop

```sh
./gradlew check                                                  # build, tests, lint, dependency rules, coverage gate
./gradlew ktlintFormat                                           # format before committing
./gradlew :cli:run --args="validate --config examples/rampguard.yml"
```

Design: [`docs/design.md`](docs/design.md). Conventions: [`AGENTS.md`](AGENTS.md).

## License

Apache-2.0. See [`LICENSE`](LICENSE).
