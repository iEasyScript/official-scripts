package com.projectx.script.impl.combat.raksha

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.BuffRequest
import com.projectx.script.api.CombatSupplies
import com.projectx.script.api.Equipment
import com.projectx.script.api.PrayerFlicker
import com.projectx.script.api.RotationManager
import com.projectx.script.api.ServerTick
import com.projectx.script.api.WarsRetreat
import com.projectx.script.api.abilityReady
import com.projectx.script.api.actionBarItemSlot
import com.projectx.script.api.adrenaline
import com.projectx.script.api.areaLootOpen
import com.projectx.script.api.awaitServerTicks
import com.projectx.script.api.castAbility
import com.projectx.script.api.effectNamed
import com.projectx.script.api.equipFromInventory
import com.projectx.script.api.equipmentClick
import com.projectx.script.api.equipmentItem
import com.projectx.script.api.groundItems
import com.projectx.script.api.healthPercent
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.lootAllAreaLoot
import com.projectx.script.api.players
import com.projectx.util.gaussian
import org.projectx.core.game.combat.Effect
import org.projectx.core.game.combat.EffectType
import java.util.concurrent.ThreadLocalRandom
import java.util.function.BooleanSupplier

internal interface RakshaFightActions {
    fun recordSafespot(): Boolean
    fun requestPoolClear()
    fun attackBoss(): Boolean
    fun canSpec(): Boolean
    fun useSpecial(name: String): Boolean
    fun drinkAdrenalinePotion(): Boolean
    fun canSummonConjures(): Boolean
    fun summonConjures(): Boolean
}

internal class RakshaFight(
    private val settings: RakshaSettings,
    private val scan: ArenaScan,
    private val mechanics: RakshaMechanics,
    private val supplies: CombatSupplies,
    private val flicker: PrayerFlicker,
    private val stats: RakshaStats,
    private val log: (String) -> Unit,
) : RakshaFightActions {
    val rotations = RakshaRotations(settings, this, log)
    val rotation = RotationManager(debug = settings.debug)

    var engaged = false
        private set
    var bossDead = false
        private set
    var transitionHolding = false
        private set
    var status = "Waiting"
        private set

    private var sawBossAlive = false
    private var sawPhase4Hp = false
    private var deadStreak = 0
    private var lastDeadTick: Long? = null
    private var lastKnownHealth = -1
    private var deadStreakWarned = false
    private var fightStartTick: Long? = null
    private var loadedPhase = 1
    private var luckEquipped = false
    private var transitionEndTick: Long? = null
    private var transitionDone = false
    private var transitionReattacked = false
    private var specialActionClicks = 0
    private var lastSpecialActionTick = -99L
    private var lootDeadline = 0L
    private var lootRecorded = false
    private var lootTimeoutLogged = false
    private var lastLootClickTick = -1L
    private var lastSpecTick = -999L
    private var lastConjureTick = -99L
    private var partnerWaitLogged = -99L
    private var partnerMissingStreak = 0
    private var lastPartnerCheckTick: Long? = null

    private val deathGrasp by lazy { effectNamed("Death Grasp") }

    private val fightBuffs: List<BuffRequest> by lazy {
        listOfNotNull(
            effectNamed("Ruination")?.let { BuffRequest("Ruination", it, BooleanSupplier { castAbility("Ruination") }, toggle = true) },
            pocketBuff("Scripture of Jas", RakshaIds.SCRIPTURE_OF_JAS),
            pocketBuff("Scripture of Ful", RakshaIds.SCRIPTURE_OF_FUL),
            pocketBuff("Erethdor's grimoire", RakshaIds.ERETHDORS_GRIMOIRE),
            BuffRequest(
                name = "Elder overload",
                effect = effectNamed("Elder overload") ?: Effect.OVERLOADED.type,
                apply = BooleanSupplier { useFirst(RakshaIds.ELDER_OVERLOAD) },
                refreshAtMillis = ThreadLocalRandom.current().nextLong(10_000, 20_001),
                canApply = BooleanSupplier { inventory.hasItem(*RakshaIds.ELDER_OVERLOAD) },
            ),
        )
    }

    private val now: Long get() = ServerTick.count

    suspend fun pass(script: Script) {
        scan.refresh()
        handleFight()
        when {
            engaged && !bossDead && killConfirmed() -> registerKill()
            bossDead && lootOnFloor() && !lootTimedOut() -> pickUpLoot(script)
            bossDead -> leave(script)
        }
    }

    fun partnerGone(): Boolean {
        if (!settings.inParty || !engaged || bossDead || transitionHolding) return false
        val tick = now
        if (tick != lastPartnerCheckTick) {
            lastPartnerCheckTick = tick
            partnerMissingStreak = if (otherPlayersHere() > 0) 0 else partnerMissingStreak + 1
        }
        return partnerMissingStreak >= PARTNER_GONE_TICKS
    }

    val partnerMissingTicks: Int get() = partnerMissingStreak

    fun reset() {
        engaged = false
        bossDead = false
        lootRecorded = false
        lastLootClickTick = -1L
        stats.dropStagedKill()
        lootDeadline = 0L
        lootTimeoutLogged = false
        sawBossAlive = false
        deadStreak = 0
        lastDeadTick = null
        lastKnownHealth = -1
        deadStreakWarned = false
        luckEquipped = false
        loadedPhase = 1
        specialActionClicks = 0
        lastSpecialActionTick = -99L
        transitionHolding = false
        transitionEndTick = null
        transitionDone = false
        transitionReattacked = false
        sawPhase4Hp = false
        partnerWaitLogged = -99L
        partnerMissingStreak = 0
        lastPartnerCheckTick = null
        fightStartTick = null
        rotation.reset()
        mechanics.resetFight()
        status = "Waiting"
    }

    fun onDeath() {
        rotation.unload()
        reset()
    }

    override fun recordSafespot(): Boolean {
        val dormant = scan.dormant ?: return false
        mechanics.setHome(dormant.centerX.toInt() - SAFESPOT_OFFSET_X, dormant.centerY.toInt())
        return false
    }

    override fun requestPoolClear() = mechanics.requestPoolClear()

    override fun attackBoss(): Boolean = mechanics.reattackBoss()

    override fun canSpec(): Boolean = adrenaline > SPEC_ADRENALINE && !specOnCooldown()

    override fun useSpecial(name: String): Boolean {
        val used = castAbility(name)
        if (used) lastSpecTick = now
        return used
    }

    override fun drinkAdrenalinePotion(): Boolean {
        supplies.holdDrinks(ADRENALINE_POTION_HOLD_TICKS)
        return useFirst(RakshaIds.ADRENALINE_POTIONS)
    }

    override fun canSummonConjures(): Boolean =
        !conjuresUp() && !conjuring() && now - lastConjureTick >= CONJURE_RECAST_TICKS && abilityReady(CONJURE_ARMY)

    override fun summonConjures(): Boolean {
        lastConjureTick = now
        return castAbility(CONJURE_ARMY)
    }

    fun conjuresUp(): Boolean = Effect.VENGEFUL_GHOST.active || Effect.SKELETON_WARRIOR.active

    fun conjuring(): Boolean = localPlayer.animationId == RakshaIds.ANIM_CONJURE

    private fun specOnCooldown(): Boolean =
        Effect.DEATH_ESSENCE_DEBUFF.active || deathGrasp?.active() == true || now - lastSpecTick < SPEC_RETRY_TICKS

    private fun handleFight() {
        val boss = scan.boss
        val phase4Hp = settings.scaled(RakshaIds.PHASE4_HP_SOLO)

        if (boss.found && boss.life in 1..phase4Hp) sawPhase4Hp = true
        if (sawPhase4Hp || transitionHolding) mechanics.setPhase(4)

        if (inPhase4Transition(boss, phase4Hp)) {
            if (!bossDead) supplies.keepUp(fightBuffs)
            if (!transitionHolding) {
                transitionHolding = true
                log("----- PHASE 4 TRANSITION: holding until it ends -----")
            }
            if (transitionEndTick != null && !transitionReattacked) {
                transitionReattacked = true
                mechanics.reattackBoss()
            }
            status = "Phase 4 transition"
            return
        }
        if (transitionHolding) {
            transitionHolding = false
            transitionDone = true
            mechanics.setPhase(4)
            log("Phase 4 transition over - resuming")
        }

        if (!engaged) {
            if (settings.inParty && otherPlayersHere() < 1) {
                if (now - partnerWaitLogged >= PARTNER_WAIT_LOG_TICKS) {
                    partnerWaitLogged = now
                    log("Duo: waiting for the other player to enter the arena")
                }
                status = "Waiting for partner"
                return
            }
            log("----- INSTANCE ENTERED: pre-fight setup -----")
            engaged = true
            rotation.load(if (settings.useRevolution) rotations.revolutionSetup else rotations.opener)
        }

        trackLiveness(boss, phase4Hp)
        swapPhaseRotation(boss)
        equipLuckRing(boss)

        if (bossDead) {
            flicker.deactivate()
            return
        }
        supplies.keepUp(fightBuffs)
        flicker.update()
        clickSpecialAction()

        var consumed = mechanics.update()
        if (!consumed && spendSurvivalAbility()) consumed = true
        if (!consumed && canSummonConjures() && summonConjures()) {
            log("Conjures gone - re-summoning")
            consumed = true
        }
        if (!consumed && mechanics.rotationOnHold) consumed = true
        if (!consumed) rotation.execute()
        status = mechanics.status
    }

    private fun inPhase4Transition(boss: BossReading, phase4Hp: Int): Boolean {
        if (loadedPhase >= 4 || transitionDone) return false
        if (boss.found && boss.life in 1..phase4Hp && boss.anim == RakshaIds.ANIM_TAIL_SWEEP_FREEDOM) {
            transitionEndTick = null
            return true
        }
        if (!transitionHolding) return false
        val end = transitionEndTick ?: now.also { transitionEndTick = it }
        return now - end < PHASE4_TRANSITION_GRACE_TICKS
    }

    private fun trackLiveness(boss: BossReading, phase4Hp: Int) {
        if (boss.found && boss.life > 0) {
            if (!sawBossAlive) {
                sawBossAlive = true
                fightStartTick = now
            }
            lastKnownHealth = boss.life
            deadStreak = 0
            lastDeadTick = null
            if (boss.life <= phase4Hp) mechanics.setPhase(4)
        } else if (sawBossAlive && now != lastDeadTick) {
            lastDeadTick = now
            deadStreak++
        }
    }

    private fun swapPhaseRotation(boss: BossReading) {
        if (settings.useRevolution || !boss.found || boss.life <= 0 || !sawBossAlive) return
        val phase = mechanics.phase
        if (phase <= loadedPhase) return
        val steps = rotations.byPhase[phase] ?: return
        loadedPhase = phase
        log("----- PHASE $phase: loading rotation -----")
        rotation.load(steps)
    }

    private fun equipLuckRing(boss: BossReading) {
        if (luckEquipped || !boss.found || mechanics.phase < 4) return
        if (boss.life !in 1..settings.scaled(RakshaIds.LUCK_RING_HP_SOLO)) return
        luckEquipped = true
        val ring = RakshaIds.LUCK_RINGS.firstOrNull { inventory.hasItem(it) }?.let { inventory.getItem(it) }
        if (ring != null && equipFromInventory(ring.name)) log("Boss at ${boss.life} hp - equipped ${ring.name}")
        else log("No luck ring in inventory to equip")
    }

    private fun spendSurvivalAbility(): Boolean {
        if (healthPercent > SURVIVAL_HP_PERCENT) return false
        val ability = SURVIVAL_ABILITIES.firstOrNull { abilityReady(it) && castAbility(it) } ?: return false
        log("Low HP - $ability")
        return true
    }

    private fun clickSpecialAction() {
        val visible = SPECIAL_ACTION_TEXT_COMPONENTS.any { interfaces.getComponent(SPECIAL_ACTION_INTERFACE, it)?.text?.isNotBlank() == true }
        if (!visible) {
            specialActionClicks = 0
            return
        }
        if (specialActionClicks >= SPECIAL_ACTION_MAX_CLICKS || now - lastSpecialActionTick < SPECIAL_ACTION_RETRY_TICKS) return
        lastSpecialActionTick = now
        specialActionClicks++
        IFSlot(SPECIAL_ACTION_INTERFACE, SPECIAL_ACTION_BUTTON).click(1)
        log("Special action clicked $specialActionClicks/$SPECIAL_ACTION_MAX_CLICKS")
    }

    private fun killConfirmed(): Boolean {
        if (scan.subduedPresent) return true
        if (!sawBossAlive) return false
        val nearlyDead = lastKnownHealth in 1..KILL_TRUST_HP
        val needed = if (nearlyDead) KILL_CONFIRM_TICKS else KILL_CONFIRM_TICKS_HIGH_HP
        if (deadStreak >= needed) return true
        if (!nearlyDead && deadStreak > KILL_CONFIRM_TICKS && !deadStreakWarned) {
            deadStreakWarned = true
            log("Boss unreadable for $deadStreak ticks but was at $lastKnownHealth hp - treating as a bad scan, not a kill")
        }
        return false
    }

    private fun registerKill() {
        bossDead = true
        lootDeadline = now + LOOT_TIMEOUT_TICKS
        flicker.deactivate()
        supplies.keepUp(emptyList())
        val durationMillis = fightStartTick?.let { (now - it) * 600 } ?: 0L
        stats.stageKill(durationMillis)
        log("Kill registered - prayers off, fight buffs releasing. Boss down in ${formatDuration(durationMillis)}")
        mechanics.review.lines().forEach { log("[REVIEW] $it") }
        status = "Looting"
    }

    private fun lootOnFloor(): Boolean = groundItems.any { it.id in RakshaIds.LOOT && localPlayer.tile.getDistance(it.tile) <= LOOT_SCAN_RANGE }

    private fun lootTimedOut(): Boolean {
        if (lootDeadline == 0L || now < lootDeadline) return false
        if (!lootTimeoutLogged) {
            lootTimeoutLogged = true
            log("Loot still on the floor after $LOOT_TIMEOUT_TICKS ticks (inventory full?) - leaving it and teleporting out")
        }
        return true
    }

    private suspend fun pickUpLoot(script: Script) {
        val lootWindow = areaLootOpen || interfaces.isOpen(LOOT_INTERFACE)
        if (!lootWindow) {
            if (now == lastLootClickTick) return
            lastLootClickTick = now
            val here = localPlayer.tile
            val pile = groundItems.filter { it.id in RakshaIds.LOOT && here.getDistance(it.tile) <= LOOT_CLICK_RANGE }
                .minByOrNull { here.getDistance(it.tile) } ?: return
            if (pile.interact("Take")) script.delayUntil(gaussian(2400L, 900L)) { areaLootOpen || interfaces.isOpen(LOOT_INTERFACE) }
            return
        }
        val uniques = if (lootRecorded) emptyList() else groundItems.filter { it.id in RakshaIds.UNIQUES && localPlayer.tile.getDistance(it.tile) <= LOOT_CLICK_RANGE }
        script.delayUntil(gaussian(1200L, 400L)) { lootWindowValue() > 0 }
        val value = lootWindowValue()
        if (!lootAllAreaLoot()) return
        if (!lootRecorded) {
            lootRecorded = true
            stats.confirmKill()
            log("----- KILL ${stats.kills} (confirmed by loot) -----")
            uniques.forEach { item ->
                val name = RakshaIds.UNIQUES[item.id] ?: "Unique ${item.id}"
                stats.addRare(name)
                log("RARE DROP: $name on kill #${stats.kills}")
            }
            stats.addLoot(value)
            log("Looted $value gp on kill #${stats.kills} (session ${stats.lootValue} gp)")
        }
        script.delayUntil(gaussian(3000L, 800L)) { !areaLootOpen && !interfaces.isOpen(LOOT_INTERFACE) }
        script.delay(420, 160)
    }

    private fun lootWindowValue(): Long {
        for (component in LOOT_VALUE_COMPONENTS) {
            val text = interfaces.getComponent(LOOT_INTERFACE, component)?.text ?: continue
            val match = LOOT_VALUE.find(text) ?: continue
            val digits = match.groupValues[1].replace(",", "")
            val multiplier = when (match.groupValues[2]) {
                "K" -> 1_000L
                "M" -> 1_000_000L
                "B" -> 1_000_000_000L
                else -> 1L
            }
            return (digits.toLongOrNull() ?: 0L) * multiplier
        }
        return 0L
    }

    private suspend fun leave(script: Script) {
        script.awaitServerTicks(2)
        if (!WarsRetreat.teleport()) {
            log("War's Retreat Teleport did not fire - retrying")
            script.delay(gaussian(1800, 600))
            return
        }
        log("Teleporting to War's Retreat")
        script.delayUntil(gaussian(9000L, 1800L)) { WarsRetreat.isHere }
        if (WarsRetreat.isHere) reset()
    }

    private fun otherPlayersHere(): Int {
        val me = localPlayer.name
        val here = localPlayer.tile
        return players.count { runCatching { it.name.isNotBlank() && it.name != me && it.tile.getDistance(here) <= 30 }.getOrDefault(false) }
    }

    private fun pocketBuff(name: String, itemId: Int): BuffRequest? {
        val effect: EffectType = effectNamed(name) ?: return null
        return BuffRequest(
            name = name,
            effect = effect,
            apply = BooleanSupplier { togglePocket(itemId, effect) },
            toggle = true,
            canApply = BooleanSupplier { equipmentItem(Equipment.Slot.POCKET)?.id == itemId },
        )
    }

    private fun togglePocket(itemId: Int, effect: EffectType): Boolean {
        if (equipmentItem(Equipment.Slot.POCKET)?.id != itemId) return false
        actionBarItemSlot(itemId)?.let { return it.click(1) }
        return equipmentClick(Equipment.Slot.POCKET, if (effect.active()) "Deactivate" else "Activate")
    }

    private fun useFirst(ids: IntArray): Boolean =
        ids.firstOrNull { inventory.hasItem(it) }?.let { inventory.getItem(it) }?.click(1) == true

    private companion object {
        const val CONJURE_ARMY = "Conjure Undead Army"
        const val SAFESPOT_OFFSET_X = 9
        const val SPEC_ADRENALINE = 23
        const val SPEC_RETRY_TICKS = 50
        const val CONJURE_RECAST_TICKS = 10
        const val ADRENALINE_POTION_HOLD_TICKS = 4
        const val SURVIVAL_HP_PERCENT = 55.0
        val SURVIVAL_ABILITIES = listOf("Devotion", "Debilitate", "Resonance", "Reflect")

        const val PHASE4_TRANSITION_GRACE_TICKS = 4
        const val KILL_CONFIRM_TICKS = 5
        const val KILL_CONFIRM_TICKS_HIGH_HP = 50
        const val KILL_TRUST_HP = 20_000
        const val LOOT_TIMEOUT_TICKS = 50
        const val LOOT_SCAN_RANGE = 70
        const val LOOT_CLICK_RANGE = 30
        const val PARTNER_WAIT_LOG_TICKS = 16
        const val PARTNER_GONE_TICKS = 25

        const val SPECIAL_ACTION_INTERFACE = 743
        const val SPECIAL_ACTION_BUTTON = 6
        const val SPECIAL_ACTION_MAX_CLICKS = 3
        const val SPECIAL_ACTION_RETRY_TICKS = 2
        val SPECIAL_ACTION_TEXT_COMPONENTS = intArrayOf(0, 1, 6)

        const val LOOT_INTERFACE = 1622
        val LOOT_VALUE_COMPONENTS = intArrayOf(4, 6, 2, 3)
        val LOOT_VALUE = Regex("Value: (?:<col=[0-9A-Fa-f]+>)?([\\d,]+)([KMB]?)")
    }
}
