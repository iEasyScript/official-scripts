# Official Scripts

First-party automation scripts for the Project X engine, maintained by the
Project X team.

These ship as the **Official** channel in the launcher's Plugins tab. Installing
that channel downloads the jar built from this repository.

## Building

Open the folder in IntelliJ IDEA and let the Gradle sync finish, or run:

```bash
./gradlew installScripts
```

The build downloads the [script API](https://github.com/iEasyScript/script-api)
itself (the version is in `gradle.properties`) and, if needed, a JDK 25. It
compiles the jar and copies it to `~/.projectx/scripts/`, where the engine loads
it on startup. To build without installing, run `./gradlew jar` and find the jar
in `build/libs/`.

## Writing a script

Scripts can be written in Kotlin or Java. Read
[WRITING-SCRIPTS.md](https://github.com/iEasyScript/script-api/blob/main/WRITING-SCRIPTS.md)
in the script-api repository, and start from the
[template](https://github.com/iEasyScript/script-template).

Two rules matter more than the rest:

- React to outcomes, never to a fixed sleep. Wait until the thing you expected
  actually happened, with a timeout.
- Never interact without a minimum interval. A loop that interacts and returns is
  re-entered on the next tick.

## Releases

A `v*` tag builds the jar and publishes it as a release asset with its sha256.
The launcher installs from the newest release.
