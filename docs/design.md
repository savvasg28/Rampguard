# Rampguard design

This document describes how Rampguard behaves and how the code is organised. It is for contributors. The reasons
behind the decisions are in the [architecture decision records](adr/README.md). The README covers installation.

Rampguard advances a Google Play staged rollout one stage per scheduled run and halts the rollout on command. It keeps
no state between runs and does not judge release health.

## Terms

| Term | Meaning |
|---|---|
| **release** | A version on the Play production track, identified by its set of version codes. |
| **live release** | The one release whose Play status is `inProgress` or `halted`. |
| **stage** | A percentage from the policy's `stages` list. |
| **step** | Setting the live release from its current stage to the next stage. The last stage completes the release. |
| **run** | One execution of a command: `tick`, `halt` or `status`. |
| **policy** | The parsed and validated `rampguard.yml`. |

## Goals and non-goals

Goals:

- Never skip a stage.
- Never write to a halted or completed release, whoever halted or completed it.
- Never write to Play during a dry run.
- Need no infrastructure beyond GitHub Actions and two secrets.

Non-goals:

- Judging release health from vitals or crash data ([ADR 0010](adr/0010-scheduled-rollouts-with-external-brakes.md)).
- Remembering anything between runs, including holds ([ADR 0011](adr/0011-stateless-the-cron-is-the-schedule.md)).
- Uploading builds, creating releases, or pressing the managed-publishing release button. The Play API offers no call
  for the last one.
- Resuming a halted release.
- iOS, and tracks other than production.

## Behaviour

### `tick`

A run reads the production track and applies the first matching row:

| The run sees | The run does |
|---|---|
| The Play API is unreadable | Nothing. Reports a guard error and exits non-zero. |
| No live release | Nothing. Reports releases that are waiting to be published or were rejected. |
| A halted release | Nothing. |
| A live release at or above the second-to-last stage | Completes it. |
| A live release at stage *n*, or between stages *n* and *n+1* | Sets it to stage *n+1*. |

The run steps whatever release is live when it fires. There is no minimum time at a stage. A release published in the
afternoon is stepped the next morning; a release published minutes before the run is stepped minutes later.

A manual run of the tick workflow does exactly what a scheduled run does.

### `halt`

The run halts the live release in Play if any requested version code is one of its version codes, so a workflow can
pass the `version-codes` output as it is. If the version codes belong to another release, or the release is already
halted, the run does nothing. The `reason` and `source` fields are untrusted text: the run removes control characters,
collapses whitespace and truncates them before use.

The version code is spelled `version-code` in every place a person or a tool writes it: the action input, the CLI
option and the `repository_dispatch` payload. The payload's older key `versionCode` is still accepted. The output is
`version-codes`, plural, because a release can ship several.

### `status`

The run prints the production track as JSON: the live release, its lifecycle and Play status, its fraction, the
stage it is at, the next step a run would take, and releases waiting for a person. The run writes nothing and records
nothing. Field names are a contract for scripts: add fields, never rename them.

### Dry run

`tick --dry-run` and `halt --dry-run` decide and report but write nothing. The audit line reads
`WOULD Advance(to=20%)`. Two independent checks prevent the write: the runner skips it, and `GuardedPlayGateway`
refuses it ([ADR 0012](adr/0012-dry-run-is-a-flag-not-a-mode.md)).

### Reporting

A run records what it saw and did in one decision record. The CLI renders that record three ways: as text on stdout,
as the same text in the GitHub Actions job summary, and as step outputs for the steps that follow in the workflow.
Rampguard sends nothing to Slack or anywhere else itself; the workflow does, with the tool's own action, gated on the
outputs ([ADR 0013](adr/0013-report-through-step-outputs.md)).

| Output | Value |
|---|---|
| `event` | What this run did to the live release: `advanced`, `completed`, `halted`, `failed` or `none`. A dry run reports `none`, because nothing was done. |
| `release-name`, `version-codes` | The live release, when there is one. |
| `from-percent`, `to-percent` | The step, on `advanced` and `completed`, in the unit `stages` uses. |
| `reason`, `source` | The sanitised halt request, on `halted`. |
| `ready-to-publish` | A release approved by review that a person has to publish. |
| `rejected` | A release rejected by review. |
| `summary` | One line saying what the run did, including what a dry run would have done. |
| `record` | Every output above as one JSON object with typed values, plus `app`, `command`, `at`, `dry-run`, `decision` and `notes`. |

The outputs are layered so that the common case costs one expression: `summary` for a message, the flat outputs for
conditions and tool inputs, `record` for everything else. A new value goes into `record` first and becomes a flat
output only when workflows need it in an `if:`.

Every output is always written, empty when it does not apply, so a workflow expression never meets an undefined value.
The names are a contract: add outputs, never rename them. The same holds for the field names inside `record`. Because runs keep no memory, a release that waits for a
person appears in `ready-to-publish` on every run that sees it; the workflow decides whether to remind people daily.

## Architecture

```
GitHub Actions (app repository)
  schedule, workflow_dispatch ──▶ rampguard tick
  repository_dispatch ──────────▶ rampguard halt      (alerting tools, relays, people)
  workflow_dispatch ────────────▶ rampguard status

rampguard
  cli/       command-line entry points, config loading, wiring; renders the record to stdout, job summary, outputs
  core/      RolloutRunner ─▶ Engine.decide(policy, observation) ─▶ DecisionRecord
             ports: PlayGateway, AuditLog, Clock
  adapters/  play (Android Publisher API)
```

The modules follow three rules:

- `core` has no I/O dependencies. A Gradle task fails the build if one appears
  ([ADR 0002](adr/0002-pure-core-with-enforced-dependency-rule.md)).
- `Engine.decide` is a pure function of the policy and the observation. It has no clock and no memory, so the same
  inputs always give the same verdict.
- Every call to the outside world goes through a port and carries a timeout.

Two runs for the same app must not overlap, because both would read Play before either writes. The example workflows
share one concurrency group named after the repository, so an installer has nothing to edit. A repository with
several apps serialises their runs, which is slower but safe. As a second line, the Play adapter re-reads the track inside its edit before it
writes ([ADR 0009](adr/0009-play-adapters.md)).

## Configuration

`.github/rampguard.yml`:

```yaml
app: com.example.app
stages: [1, 5, 20, 50, 100]
```

Validation is strict. Unknown keys fail the job before any API call. Keys from earlier designs (`gates`,
`minSoakHours`, `blackout`, `mode`, `notify`) fail with a message that names the ADR that removed them.

## Failure modes

| Failure | Behaviour |
|---|---|
| Play API unreadable, 5xx, or timeout | No write. Guard error reported. Job exits non-zero. |
| Release not on the track, or not in progress, at write time | Adapter refuses the write. The next run re-reads. |
| Edit conflict with a concurrent change | Adapter re-reads and never advances twice ([ADR 0009](adr/0009-play-adapters.md)). |
| Commit times out | Not retried. The next run re-reads. |
| Write fails | Guard error reported. The next run tries again. |
| Malformed config or command payload | Job fails before any API call. |

## Security

- The Play service account should see one app only, with *View app information* and *Release to production*
  permissions. A dry run needs the same permissions, because nothing in Play distinguishes it until the write.
- Anyone whose token can send `repository_dispatch` to the repository can halt the rollout. Use a fine-grained token
  per sender.
- The only secret Rampguard holds is the Play key. It never appears in logs, audit output, job summaries, step outputs
  or tests. Tokens for Slack, Sentry or Datadog stay in the workflow steps that use them.
