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

## Scripts

### GE Flipper

Flips items on the Grand Exchange. It buys one coin above an item's latest
instant-sell price and sells one coin below its latest instant-buy price, using
live prices from the [RuneScape Wiki](https://prices.runescape.wiki/rs), and only
takes flips that still profit after the exchange's 2% sales tax. Start it next to
a Grand Exchange clerk; it opens the exchange itself.

- It sells before it buys: anything in the backpack at start, and everything a
  buy offer brings in, is listed for sale before a new buy goes in.
- Offers that do not finish are aborted and re-priced. Items it bought are never
  sold below cost until they have been held for the configured time.
- It tracks the offers it placed and the items it holds in
  `~/.projectx/script-data/ge-flipper/`, so a restart picks up where it left off.
  Offers you place yourself are left alone unless you turn on *Take over existing
  offers*.
- Settings cover the item list (or automatic picking by volume and price), slots,
  coins per offer, coins to keep, minimum profit, re-pricing and breaks.

### AIO Agility

Runs laps of an agility course until you stop it. Start it anywhere on the
course; with the course left on *Automatic* it picks the one you are at.

| Course | Notes |
|---|---|
| Wilderness | It does not watch your health or run from other players. This is the real Wilderness: bring nothing you would mind losing. |
| Hefin (Prifddinas) | Takes the cathedral window shortcut whenever it is open, and merges with the light creature at the end of each lap. |
| Anachronia | The full 52-obstacle lap around the island, from the Temple wall by the lodestone. Every section is taken, so it needs the level for the hardest one (85). |

- It works out the next obstacle from where you are standing, so it picks up
  mid-lap and after a restart.
- Each obstacle is clicked by its exact tile, so a second object with the same
  name nearby (like the far end of the Wilderness pipe) is never clicked instead.
- After a fall it climbs out of any pit and carries on from the nearest obstacle
  it can reach.

## Releases

A `v*` tag builds the jar and publishes it as a release asset with its sha256.
The launcher installs from the newest release.
