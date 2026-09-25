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

Every skilling script here captures a Seren spirit when one appears while you
wear a Grace of the elves, so its reward reaches your bank. Each also shows an
overlay with its runtime, XP gained and per hour, time to the next level, and
what it has done (laps, conversions, bones buried and so on).

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

### AIO Divination

Harvests wisps at any colony, from Pale to Incandescent and the Elder wisps.
Start it at the colony; it picks up the wisps around you.

- Enriched wisps and springs are taken first, and a normal harvest is dropped
  as soon as an enriched one appears.
- A full backpack of memories is converted at the energy rift, using whatever
  conversion the rift is set to.
- Chronicle fragments are caught as they fly past, and empowered at the rift
  once you hold the amount set under *Empower at*. *Only chronicle fragments*
  waits at the colony for them without harvesting.

### Archaeology Qualifications

Works an account up the Archaeology qualifications - Intern, Assistant,
Associate, Professor, Guildmaster - by doing whichever of their requirements is
still outstanding. Start it at a dig site, or at the guild to do a restoration
run.

- It keeps no progress of its own. The qualification held, artefacts excavated
  and restored, collections finished, mysteries solved and research banked are
  all read from the account's own vars, so stopping it and starting it again a
  week later carries on from exactly where it was.
- Each pass it picks the job that moves the next qualification on: get a
  research contract out, restore what has been dug, take finished artefacts to
  their collector, or dig the best hotspot it can reach. When the last
  requirement lands it attends the ceremony that actually awards the rank.
- Hotspots, restoration recipes and collections come from the game cache, so it
  digs the best hotspot for your level without a hand-kept list, and it will
  choose a hotspot that feeds a collection you are close to finishing.
- Materials go to storage through the dig site's own cart as they arrive, soil
  goes into the soil box and is screened on the trip home, and only artefacts
  ride back with you.
- The way to each hotspot is learned the first time it stands at one and kept in
  `~/.projectx/script-data/archaeology-hotspots.txt`, so later runs walk
  straight back. The Archaeology journal must be in your backpack: it is what
  teleports home.
- Two things it does not do. Mysteries are twenty hand-built puzzle chains, so
  it counts them and tells you how many are left. Research needs a team you have
  hired; it will send an existing team out, but it will not spend your chronotes
  hiring one.

### Bank Burier

Buries bones or scatters ashes next to a bank, keeping Powder of burials active
the whole time. Pick what to use under *Bones or ashes*: every bone and ash
that can be bought on the Grand Exchange is listed. Start it next to a bank with
the bones or ashes and some Powder of burials in it.

- It works at any bank booth, bank chest, counter or banker.
- When the powder wears off it takes one from the bank and scatters it before
  burying anything else.
- Anything else in the backpack is deposited first, and bank withdrawals are
  switched off notes.
- It stops when the bank runs out of the chosen bones or ashes, or of powder.

### Raksha

Kills Raksha, the Shadow Colossus (normal mode) with Necromancy, solo or as a
duo. Start it anywhere; it teleports to War's Retreat and runs the whole loop.

- At War's Retreat it restores prayer at the altar, loads the last bank preset
  (entering the bank PIN when asked), fills adrenaline at the crystal, builds
  5 residual souls and 12 necrosis on the training dummy and enters the portal.
  The order of those stops is a setting.
- In the lobby it conjures the undead army, casts Life Transfer into it, eats,
  then rejoins the live instance or starts a new one.
- Each phase runs its own scripted rotation from the PVME guide, falling back to
  an adaptive Necromancy filler. Prayers are flicked against Raksha's autos,
  with Soul Split in between.
- Every mechanic is answered: floor shadows and the insta-kill highlight are
  dodged first, tail sweeps are escaped, bombs are walked clear of, Shadow energy
  is expelled, the manifestation is stunned and killed, and anima pools are
  cleared in phase 3 when more than the configured number are standing.
- It eats, drinks and keeps Ruination, the pocket scripture and the overload up,
  swaps in a luck ring near the end, loots through the loot window and reclaims
  items at Death's Office after a death.
- Curses, a Necromancy action bar and a preset with vulnerability bombs, blue
  blubber jellyfish, an elder overload and an adrenaline potion are required.

Its rotation and prayer handling follow Sonson's Lua core modules (rotation
manager, prayer flicker, player manager and War's Retreat), used under the
Apache License 2.0.

## Releases

A `v*` tag builds the jar and publishes it as a release asset with its sha256.
The launcher installs from the newest release.
