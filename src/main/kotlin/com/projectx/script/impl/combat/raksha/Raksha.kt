package com.projectx.script.impl.combat.raksha

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigVisibilityProvider
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.StringConfigItem
import com.projectx.script.api.CombatSupplies
import com.projectx.script.api.Prayer
import com.projectx.script.api.PrayerFlicker
import com.projectx.script.api.PrayerThreat
import com.projectx.script.api.RotationManager
import com.projectx.script.api.ServerTick
import com.projectx.script.api.Threshold
import com.projectx.script.api.WarsRetreat
import com.projectx.script.api.WarsRetreatTask
import com.projectx.script.api.WarsRetreatTrip
import com.projectx.script.api.awaitServerTick
import com.projectx.script.api.localPlayer
import com.projectx.script.api.adrenaline
import com.projectx.script.api.familiarCastSpecial
import com.projectx.script.api.familiarScrolls
import com.projectx.script.api.familiarSpecialPoints
import com.projectx.script.api.familiarSummoned
import com.projectx.script.api.familiarTimeSeconds
import com.projectx.script.api.healthPercent
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.prayerPercent
import com.projectx.script.api.runWarsRetreatTrip
import com.projectx.script.api.summoningPointsPercent
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.readout
import com.projectx.ui.backend.dsl.scopes.section
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.valueRow
import com.projectx.util.gaussian

@ScriptDescription(
    name = "Raksha",
    version = "1.0.3",
    author = "Cryptic",
    description = "Kills Raksha, the Shadow Colossus (normal mode) with Necromancy: banks and prebuilds at War's " +
        "Retreat, conjures in the lobby, fights all four phases with prayer flicking and every mechanic answered, " +
        "loots, and repeats. Curses, a Necromancy bar and a War's Retreat preset are required.",
    category = ScriptCategory.COMBAT,
)
class Raksha : Script(), ConfigurableScript, ConfigVisibilityProvider {

    private val generalSection = ConfigSection("General")
    private val bankPin = StringConfigItem("Bank PIN", "Four digits, entered when the bank asks. Leave blank if you have none.", "")
    private val waitForFullHp = BooleanConfigItem("Wait for full HP", "Hold at War's Retreat until health is topped up before entering.", true)
    private val useRevolution = BooleanConfigItem(
        "Use Revolution",
        "Your Revolution bar does the damage. The script still does setup, positioning, prayers, mechanics and food.",
        false,
    )
    private val useFamiliar = BooleanConfigItem(
        "Ripper demon familiar",
        "Summon, renew and spec a Ripper demon. Its pouch and 20 scrolls must be in the preset.",
        true,
    )
    private val debugLogging = BooleanConfigItem("Debug logging", "Log every mechanic decision.", true)

    private val partySection = ConfigSection("Party", defaultOpen = false)
    private val inParty = BooleanConfigItem("Duo (in a party)", "Raksha's phases double with a second player.", false)
    private val isPartyLeader = BooleanConfigItem("I own the instance", "The owner creates a two-player instance; the other player joins it.", false)
    private val partyLeader = StringConfigItem("Owner's name", "The instance owner's display name, for joining.", "")

    private val healthSection = ConfigSection("Health")
    private val healthSolid = IntConfigItem("Solid food (%)", "Eat solid food at or below this health.", 70, 0, 100)
    private val healthJellyfish = IntConfigItem("Jellyfish (%)", "Eat a blubber jellyfish at or below this health.", 70, 0, 100)
    private val healthPotion = IntConfigItem("Healing potion (%)", "Drink a brew at or below this health.", 60, 0, 100)
    private val healthSpecial = IntConfigItem("Excalibur (%)", "Activate Enhanced Excalibur at or below this health.", 75, 0, 100)

    private val prayerSection = ConfigSection("Prayer")
    private val prayerNormal = IntConfigItem("Restore below (points)", "Drink a restore at or below this many prayer points.", 400, 0, MAX_PRAYER_POINTS)
    private val prayerCritical = IntConfigItem("Critical (%)", "With no restores left at this prayer, teleport out.", 10, 0, 100)
    private val prayerSpecial = IntConfigItem("Elven shard below (points)", "Use the ancient elven ritual shard at or below this.", 601, 0, MAX_PRAYER_POINTS)

    private val warsSection = ConfigSection("War's Retreat")
    private val summonConjures = BooleanConfigItem("Summon conjures", "Summon conjures at War's Retreat. They are summoned in the lobby anyway.", false)
    private val usePrebuild = BooleanConfigItem("Use prebuild", "Build 5 souls and 12 necrosis on the training dummy before entering.", true)
    private val useAdrenCrystal = BooleanConfigItem("Use adrenaline crystal", "Fill adrenaline at the crystal.", true)
    private val bankIfInvFull = BooleanConfigItem("Bank if inventory full", "Reload the preset whenever the backpack is full.", false)
    private val advancedMovement = BooleanConfigItem("Advanced movement", "Surge and dive around War's Retreat: arrival to bank, bank to crystal, bank to portal, dummies to portal. Needs Surge and Dive on a bar.", false)
    private val surgeDiveChance = IntConfigItem("Surge/Dive chance (%)", "How often the advanced movement shortcut is taken.", 100, 0, 100)
    private val minPrayer = IntConfigItem("Altar below prayer (%)", "Pray at the altar when prayer is below this.", 100, 0, 100)
    private val minSummoning = IntConfigItem("Altar below summoning (%)", "Pray at the altar when summoning is below this.", 80, 0, 100)
    private val minHealth = IntConfigItem("Wait below health (%)", "With Wait for full HP on, heal up when health is below this.", 80, 0, 100)
    private val warsTaskOrder = StringConfigItem(
        "Task order",
        "The War's Retreat stops in order: ALTAR, BANK, CRYSTAL, CONJURES, PREBUILD, PORTAL. Missing stops are added before PORTAL.",
        "ALTAR, BANK, CRYSTAL, PREBUILD, PORTAL",
    )

    private val mechanicsSection = ConfigSection("Mechanics")
    private val ignoreAnimaPools = BooleanConfigItem("Ignore anima pools", "Never break off to clear pools. Uncleared pools heal Raksha.", false)
    private val poolKillThreshold = IntConfigItem(
        "Pool kill threshold",
        "Phase 3 only: this many pools standing is fine; above it they are cleared back down. A siphon clears them all.",
        10,
        1,
        20,
    )
    private val poolDiveDistance = IntConfigItem("Pool dive distance", "Dive to a pool further than this many tiles.", 8, 2, 20)
    private val shadowTriggerRange = IntConfigItem("Shadow trigger range", "Dodge a floor shadow within this many tiles of its edge.", 1, 1, 4)
    private val shadowSafeRange = IntConfigItem("Shadow safe range", "Move this many tiles clear of a floor shadow's edge.", 2, 1, 4)
    private val instakillTriggerRange = IntConfigItem("Insta-kill trigger range", "Dodge the insta-kill highlight within this many tiles.", 5, 1, 12)
    private val instakillSafeRange = IntConfigItem("Insta-kill safe range", "Move this many tiles clear of the insta-kill highlight.", 6, 1, 15)
    private val bombEscapeDistance = IntConfigItem("Bomb escape distance", "Stand this many tiles clear of ground bombs.", 5, 1, 12)

    private lateinit var settings: RakshaSettings
    private var pendingSettings: RakshaSettings? = null
    private lateinit var scan: ArenaScan
    private lateinit var mechanics: RakshaMechanics
    private lateinit var supplies: CombatSupplies
    private lateinit var flicker: PrayerFlicker
    private lateinit var fight: RakshaFight
    private lateinit var lobby: RakshaLobby
    private lateinit var death: DeathRecovery
    private val stats = RakshaStats()

    private var status = "Starting"
    private var location = "Unknown"
    private var bossLine = "-"
    private var mechanicLine = "-"
    private var reviewLine = "-"
    private var recentSteps: List<String> = emptyList()
    private var lastFamiliarSpecialTick = -99L
    private var currentStage = ""
    private var unknownStillTicks = 0
    private var unknownLastTick = -1L
    private var unknownLastPosition: Triple<Int, Int, Int>? = null
    private var unknownTicksNeeded = 0

    private inner class Stage(val name: String, val applies: () -> Boolean, val run: suspend () -> Unit)

    private val stages = listOf(
        Stage("Dead", { death.check(fight.engaged) }) { death.recover(this) { onRecovered() } },
        Stage("Duo partner gone", { fight.partnerGone() }) { leaveForPartner() },
        Stage("Raksha arena", { !WarsRetreat.isHere && inInstancedArea }) { fightPass() },
        Stage("Raksha lobby", { lobby.isHere }) { lobby.pass(this) },
        Stage("War's Retreat", { WarsRetreat.isHere }) { warsRetreatVisit() },
        Stage(UNKNOWN_STAGE, { true }) { unknownLocation() },
    )

    override fun onStart() {
        settings = readSettings()
        pendingSettings = null
        scan = ArenaScan()
        mechanics = RakshaMechanics(settings, scan) { if (settings.debug) println("[Raksha][MECH] $it") }
        supplies = CombatSupplies(
            food = Threshold.percent(settings.healthSolid),
            jellyfish = Threshold.percent(settings.healthJellyfish),
            healingPotion = Threshold.percent(settings.healthPotion),
            excalibur = Threshold.percent(settings.healthSpecial),
            prayerPotion = Threshold.fixed(settings.prayerNormal),
            criticalPrayer = Threshold.percent(settings.prayerCritical),
            elvenShard = Threshold.fixed(settings.prayerSpecial),
            brewSipsPerRestore = 3,
            comboBrewWithJellyfish = true,
        )
        flicker = PrayerFlicker(
            Prayer.SOUL_SPLIT,
            listOf(
                bossAnimationThreat("Raksha magic auto", Prayer.DEFLECT_MAGIC, RakshaIds.ANIM_ATTACK_MAGIC).priority(10).durationTicks(2),
                bossAnimationThreat("Raksha ranged auto", Prayer.DEFLECT_RANGE, RakshaIds.ANIM_ATTACK_RANGED).priority(10).durationTicks(2),
                bossAnimationThreat("Raksha melee auto", Prayer.DEFLECT_MELEE, RakshaIds.ANIM_ATTACK_MELEE).priority(9).durationTicks(2),
                bossAnimationThreat("Shadow bomb impact", Prayer.DEFLECT_MAGIC, RakshaIds.ANIM_SHADOW_BOMBARDMENT).priority(20).durationTicks(4),
            ),
        )
        fight = RakshaFight(settings, scan, mechanics, supplies, flicker, stats, ::log)
        lobby = RakshaLobby(settings, fight, ::log)
        death = DeathRecovery(stats, ::log)

        log("Raksha phases at ${settings.scaled(RakshaIds.PHASE2_HP_SOLO)} / ${settings.scaled(RakshaIds.PHASE3_HP_SOLO)} / ${settings.scaled(RakshaIds.PHASE4_HP_SOLO)}${if (settings.inParty) " (duo)" else ""}")
        log("War's Retreat task order: ${settings.warsTaskOrder.joinToString(" -> ")}")
        if (bankPin.value.isNotBlank() && pinOrNull() == null) log("Bank PIN must be four digits - it will not be entered")
        flicker.missingPrayers.takeIf { it.isNotEmpty() }?.let { missing ->
            log("Not on an action bar, so the flicker cannot use them: ${missing.joinToString()}")
        }
    }

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (!WarsRetreat.isHere) supplies.update()
        val stage = stages.first { it.applies() }
        if (stage.name != currentStage) {
            log("Stage: ${currentStage.ifEmpty { "start" }} -> ${stage.name}")
            currentStage = stage.name
        }
        location = stage.name
        if (stage.name != "Raksha lobby") lobby.leftLobby()
        if (stage.name != UNKNOWN_STAGE) resetUnknownLocation()
        stage.run()
        refreshOverlay()
    }

    override fun onEvent(event: Event) {
        if (event is Chat && ::mechanics.isInitialized) mechanics.onChat(event.message)
    }

    override fun isConfigItemVisible(fieldName: String, item: ConfigItem<*>): Boolean = when (item) {
        isPartyLeader -> inParty.value
        partyLeader -> inParty.value && !isPartyLeader.value
        surgeDiveChance -> advancedMovement.value
        poolKillThreshold, poolDiveDistance -> !ignoreAnimaPools.value
        else -> true
    }

    private suspend fun fightPass() {
        fight.pass(this)
        useFamiliarSpecial()
        status = fight.status
    }

    private fun useFamiliarSpecial() {
        if (!settings.useFamiliar || !fight.engaged || fight.bossDead || !familiarSummoned) return
        val now = ServerTick.count
        if (now - lastFamiliarSpecialTick < FAMILIAR_SPECIAL_EVERY_TICKS) return
        if (familiarSpecialPoints < FAMILIAR_SPECIAL_COST) return
        if (!inventory.hasItem(RakshaIds.RIPPER_DEMON_SCROLL) && familiarScrolls <= 0) return
        lastFamiliarSpecialTick = now
        if (familiarCastSpecial()) mechanicsLog("Familiar special: Death From Above")
    }

    private suspend fun warsRetreatVisit() {
        applyPendingSettings()
        if (fight.engaged || fight.bossDead) {
            log("Back at War's Retreat - clearing fight state (adrenaline ${adrenaline.toInt()})")
            fight.reset()
        }
        lobby.resetTrip()
        flicker.deactivate()
        val loadPreset = !loadoutPresent() || (settings.bankIfInvFull && inventory.isFull)
        val prayAtAltar = prayerPercent < settings.minPrayer || summoningPointsPercent < settings.minSummoning
        for (task in settings.warsTaskOrder) {
            status = "War's Retreat: ${task.name.lowercase()}"
            val done = when (task) {
                WarsRetreatTask.PREBUILD -> {
                    if (settings.usePrebuild) prebuild()
                    true
                }
                WarsRetreatTask.PORTAL -> {
                    upkeepFamiliar()
                    runWarsRetreatTrip(trip(listOf(task), loadPreset, prayAtAltar)) && awaitLobby()
                }
                else -> runWarsRetreatTrip(trip(listOf(task), loadPreset, prayAtAltar))
            }
            if (!done) {
                delay(900, 400)
                return
            }
        }
    }

    private suspend fun awaitLobby(): Boolean {
        status = "Entering the Raksha lobby"
        delayUntil(gaussian(8000L, 1500L), FAST_POLL_MILLIS) { lobby.isHere || inInstancedArea }
        if (!lobby.isHere && !inInstancedArea) log("Left War's Retreat but the Raksha lobby has not loaded yet")
        return true
    }

    private suspend fun unknownLocation() {
        val position = Triple(localPlayer.tileX, localPlayer.tileY, localPlayer.plane)
        val settled = !localPlayer.isMoving && !localPlayer.isAnimating && position == unknownLastPosition
        unknownLastPosition = position
        val now = ServerTick.count
        if (!settled) unknownStillTicks = 0
        else if (now != unknownLastTick) unknownStillTicks++
        unknownLastTick = now
        if (unknownTicksNeeded == 0) unknownTicksNeeded = gaussian(UNKNOWN_SETTLE_TICKS, UNKNOWN_SETTLE_VARIANCE).coerceIn(5, 8)
        if (unknownStillTicks < unknownTicksNeeded) {
            status = "Waiting for the scene to load"
            awaitServerTick()
            return
        }
        log(
            "Not at War's Retreat, the Raksha lobby or the arena for $unknownStillTicks still ticks at " +
                "(${position.first}, ${position.second}, ${position.third}) - teleporting to War's Retreat",
        )
        resetUnknownLocation()
        status = "Teleporting to War's Retreat"
        if (WarsRetreat.teleport()) delayUntil(gaussian(9000L, 1800L)) { WarsRetreat.isHere }
        else {
            log("War's Retreat Teleport did not fire - retrying")
            delay(1800, 600)
        }
    }

    private fun resetUnknownLocation() {
        unknownStillTicks = 0
        unknownLastTick = -1L
        unknownLastPosition = null
        unknownTicksNeeded = 0
    }

    private fun trip(order: List<WarsRetreatTask>, loadPreset: Boolean, prayAtAltar: Boolean) = WarsRetreatTrip(
        portalName = RakshaIds.PORTAL_NAME,
        loadPreset = loadPreset,
        prayAtAltar = prayAtAltar,
        useAdrenalineCrystal = settings.useAdrenCrystal,
        summonConjures = settings.summonConjures,
        waitForFullHealth = settings.waitForFullHp && healthPercent < settings.minHealth,
        order = order,
        bankPin = pinOrNull(),
    ).apply {
        advancedMovement = settings.advancedMovement
        advancedMovementChance = settings.surgeDiveChance
    }

    private suspend fun prebuild() {
        val rotations = fight.rotations
        if (rotations.prebuildComplete()) return
        status = "Prebuilding on the training dummy"
        val manager = RotationManager()
        rotations.resetPrebuild()
        rotations.restartPrebuild = { manager.reset() }
        manager.load(rotations.prebuild)
        val deadline = System.currentTimeMillis() + gaussian(PREBUILD_TIMEOUT_MS, PREBUILD_TIMEOUT_VARIANCE)
        while (!stopped && !manager.isFinished && System.currentTimeMillis() < deadline) {
            if (!WarsRetreat.isHere) return
            manager.execute()
            delay(90, 30)
        }
    }

    private suspend fun upkeepFamiliar() {
        if (!settings.useFamiliar) return
        if (!familiarSummoned) {
            val pouch = inventory.getItem(RakshaIds.RIPPER_DEMON_POUCH) ?: return
            status = "Summoning Ripper demon"
            log("Summoning Ripper demon")
            if (pouch.click("Summon")) {
                delayUntil(gaussian(2600L, 500L), FAST_POLL_MILLIS) { familiarSummoned }
                delay(150, 60)
            }
            return
        }
        if (familiarTimeSeconds >= FAMILIAR_RENEW_BELOW_SECONDS) return
        if (!inventory.hasItem(RakshaIds.RIPPER_DEMON_POUCH) || !interfaces.isOpen(FOLLOWER_DETAILS)) return
        log("Renewing familiar (${familiarTimeSeconds}s left)")
        val pouches = inventory.count(RakshaIds.RIPPER_DEMON_POUCH)
        if (IFSlot(FOLLOWER_DETAILS, FOLLOWER_RENEW).click(1)) {
            delayUntil(gaussian(3000L, 600L), FAST_POLL_MILLIS) { inventory.count(RakshaIds.RIPPER_DEMON_POUCH) < pouches }
            delay(150, 60)
        }
    }

    private suspend fun leaveForPartner() {
        if (!WarsRetreat.teleport()) {
            log("Duo: partner gone, but the teleport did not fire - retrying")
            delay(1800, 600)
            return
        }
        log("Duo: no partner for ${fight.partnerMissingTicks} ticks - teleporting to War's Retreat to reset")
        fight.reset()
        delayUntil(gaussian(9000L, 1800L)) { WarsRetreat.isHere }
    }

    private fun onRecovered() {
        applyPendingSettings()
        fight.onDeath()
        lobby.resetTrip()
    }

    fun onConfigUpdated() {
        if (!::lobby.isInitialized) return
        val next = readSettings()
        val now = next.withTripFieldsOf(settings)
        if (fight.engaged && now != next) {
            if (next != pendingSettings) log("Party and Revolution changes apply from the next trip")
            pendingSettings = next
            applySettings(now)
        } else {
            pendingSettings = null
            applySettings(next)
        }
    }

    private fun applyPendingSettings() {
        val pending = pendingSettings ?: return
        pendingSettings = null
        applySettings(pending)
        log("Applied the settings saved during the last trip")
    }

    private fun applySettings(next: RakshaSettings) {
        settings = next
        mechanics.settings = next
        fight.settings = next
        lobby.settings = next
        supplies.food = Threshold.percent(next.healthSolid)
        supplies.jellyfish = Threshold.percent(next.healthJellyfish)
        supplies.healingPotion = Threshold.percent(next.healthPotion)
        supplies.excalibur = Threshold.percent(next.healthSpecial)
        supplies.prayerPotion = Threshold.fixed(next.prayerNormal)
        supplies.criticalPrayer = Threshold.percent(next.prayerCritical)
        supplies.elvenShard = Threshold.fixed(next.prayerSpecial)
    }

    private fun loadoutPresent(): Boolean {
        val required = buildList {
            add(intArrayOf(RakshaIds.VULNERABILITY_BOMB) to 502)
            add(intArrayOf(RakshaIds.BLUE_BLUBBER_JELLYFISH) to 10)
            add(RakshaIds.ELDER_OVERLOAD to 1)
            add(RakshaIds.ADRENALINE_POTIONS to 1)
            if (settings.useFamiliar) {
                add(intArrayOf(RakshaIds.RIPPER_DEMON_POUCH) to 1)
                add(intArrayOf(RakshaIds.RIPPER_DEMON_SCROLL) to 20)
            }
        }
        return required.all { (ids, amount) -> ids.any { inventory.count(it) >= amount } }
    }

    private fun pinOrNull(): String? = bankPin.value.trim().takeIf { it.length == 4 && it.all(Char::isDigit) }

    private fun bossAnimationThreat(name: String, prayer: Prayer, animation: Int) =
        PrayerThreat.animation(name, prayer, RakshaIds.BOSS, THREAT_RANGE, animation)

    private fun readSettings() = RakshaSettings(
        waitForFullHp = waitForFullHp.value,
        useRevolution = useRevolution.value,
        inParty = inParty.value,
        isPartyLeader = isPartyLeader.value,
        partyLeader = partyLeader.value,
        useFamiliar = useFamiliar.value,
        healthSolid = healthSolid.value,
        healthJellyfish = healthJellyfish.value,
        healthPotion = healthPotion.value,
        healthSpecial = healthSpecial.value,
        prayerNormal = prayerNormal.value,
        prayerCritical = prayerCritical.value,
        prayerSpecial = prayerSpecial.value,
        summonConjures = summonConjures.value,
        usePrebuild = usePrebuild.value,
        useAdrenCrystal = useAdrenCrystal.value,
        bankIfInvFull = bankIfInvFull.value,
        advancedMovement = advancedMovement.value,
        surgeDiveChance = surgeDiveChance.value,
        minPrayer = minPrayer.value,
        minSummoning = minSummoning.value,
        minHealth = minHealth.value,
        warsTaskOrder = RakshaSettings.parseTaskOrder(warsTaskOrder.value).let { order ->
            if (summonConjures.value && WarsRetreatTask.CONJURES !in order)
                order.toMutableList().apply { add(indexOf(WarsRetreatTask.PORTAL).coerceAtLeast(0), WarsRetreatTask.CONJURES) }
            else order
        },
        ignoreAnimaPools = ignoreAnimaPools.value,
        poolKillThreshold = poolKillThreshold.value,
        poolDiveDistance = poolDiveDistance.value,
        shadowTriggerRange = shadowTriggerRange.value,
        shadowSafeRange = shadowSafeRange.value,
        instakillTriggerRange = instakillTriggerRange.value,
        instakillSafeRange = instakillSafeRange.value,
        bombEscapeDistance = bombEscapeDistance.value,
        debug = debugLogging.value,
    )

    private fun refreshOverlay() {
        if (!::lobby.isInitialized) return
        val boss = scan.boss
        val review = mechanics.review
        bossLine = if (boss.found && boss.life > 0) "%,d / %,d hp (phase %d)".format(boss.life, review.bossMaxSeen, mechanics.phase) else "-"
        mechanicLine = if (location == "Raksha arena") mechanics.status + mechanics.lastHandler.let { if (it.isEmpty()) "" else " [$it]" } else "-"
        reviewLine = "taken %,d, biggest %,d (%s), missed %d".format(review.damageTaken, review.biggestHit, review.biggestHitDuring, review.missed)
        recentSteps = fight.rotation.recentSteps.take(3).map { "${it.label}${if (it.succeeded) "" else " (not fired)"}" }
        if (location == "Dead") status = death.status
        if (location == "Raksha lobby") status = lobby.status
    }

    override fun render() {
        val now = System.currentTimeMillis()
        ImGuiDsl.window("Raksha") {
            readout("raksha-state") {
                valueRow("Runtime", formatDuration(now - stats.startedAt))
                valueRow("Location", location)
                valueRow("Status", status)
                valueRow("Boss", bossLine)
                valueRow("Mechanic", mechanicLine)
            }
            separator()
            readout("raksha-stats") {
                valueRow("Kills", "${stats.kills} (${stats.perHour(stats.kills.toLong())}/hr)")
                valueRow("Deaths", stats.deaths.toString())
                valueRow("Kill times", "${formatDuration(stats.fastestKill)} / ${formatDuration(stats.averageKill)} / ${formatDuration(stats.slowestKill)}")
                valueRow("Loot", "%,d gp (%,d/hr)".format(stats.lootValue, stats.perHour(stats.lootValue)))
                valueRow("Best pile", "%,d gp".format(stats.bestLoot))
                valueRow("Damage", reviewLine)
            }
            if (recentSteps.isNotEmpty()) {
                section("Rotation")
                recentSteps.forEach { text(it) }
            }
            if (stats.rares.isNotEmpty()) {
                section("Rare drops")
                stats.rares.take(5).forEach { text("${it.name} (kill ${it.kill})") }
            }
        }
    }

    override fun onStop() {
        if (::flicker.isInitialized) flicker.deactivate()
        println("[Raksha] Stopped after ${stats.kills} kills, ${stats.deaths} deaths, ${stats.lootValue} gp looted")
    }

    private fun log(message: String) = println("[Raksha] $message")

    private fun mechanicsLog(message: String) {
        if (settings.debug) println("[Raksha] $message")
    }

    private companion object {
        const val THREAT_RANGE = 60
        const val UNKNOWN_STAGE = "Unknown location"
        const val UNKNOWN_SETTLE_TICKS = 6
        const val UNKNOWN_SETTLE_VARIANCE = 1
        const val MAX_PRAYER_POINTS = 990
        const val FOLLOWER_DETAILS = 662
        const val FOLLOWER_RENEW = 53
        const val FAMILIAR_SPECIAL_COST = 20
        const val FAMILIAR_SPECIAL_EVERY_TICKS = 8
        const val FAMILIAR_RENEW_BELOW_SECONDS = 300
        const val FAST_POLL_MILLIS = 30
        const val PREBUILD_TIMEOUT_MS = 180_000L
        const val PREBUILD_TIMEOUT_VARIANCE = 20_000L
    }
}
