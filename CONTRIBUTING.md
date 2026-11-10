# Contributing

Thank you for considering a contribution. This page tells you how to build, test and submit a change.

## Build and test

You need JDK 21. Everything else comes through the Gradle wrapper.

```sh
./gradlew check            # build, tests, ktlint, detekt, dependency rules, coverage gate
./gradlew ktlintFormat     # format before committing
```

`check` must pass before you open a pull request. CI runs the same command.

## Where things live

| Directory | Contents |
|---|---|
| `core/` | The decision engine, the policy parser and the ports. No I/O. |
| `adapters/play/` | The Android Publisher API adapter. |
| `cli/` | Command-line entry points and adapter wiring. |
| `docs/` | The design document and the architecture decision records. |
| `examples/` | The configuration file, the two workflows that installers copy, and the halt and notify recipes. |

`AGENTS.md` lists the architecture rules that the build enforces, for people and coding agents alike. `docs/design.md` describes the behaviour.

## Make a change

1. For a behaviour change, start with a scenario in `core/src/test/.../engine/ScenarioTest.kt` or a property in
   `EnginePropertyTest.kt`. Scenario tests assert the exact decision timeline.
2. Keep `core` free of I/O. The build fails if a forbidden dependency appears.
3. If you change a design decision, add or amend a record in `docs/adr/`. Keep it short: context, decision,
   consequences.
4. Write comments that say what the code does, not why a document asked for it.
5. Open a pull request with a description of the behaviour change and the test that proves it.

## Report a bug

Open an issue with the Rampguard version, the configuration file with the package name removed, and the job log of
the failing run with secrets removed. For security problems, see `SECURITY.md` instead.
