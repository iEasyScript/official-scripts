package com.projectx.script.impl.combat.raksha

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.ServerTick
import com.projectx.script.api.abilityReady
import com.projectx.script.api.castAbility
import com.projectx.script.api.diveToTile
import com.projectx.script.api.healthCurrent
import com.projectx.script.api.walkToTile
import com.projectx.script.impl.combat.raksha.ArenaGeometry.distance
import com.projectx.script.impl.combat.raksha.ArenaGeometry.escapeClearance
import com.projectx.script.impl.combat.raksha.ArenaGeometry.pathHazardCost
import com.projectx.script.impl.combat.raksha.ArenaGeometry.tileIsClear
import com.projectx.util.gaussian
import org.projectx.core.game.combat.Effect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

internal class RakshaMechanics(
    var settings: RakshaSettings,
    private val scan: ArenaScan,
    private val log: (String) -> Unit,
) {
    private class Handler(val name: String, val applies: () -> Boolean, val respond: () -> Boolean)

    var phase = 1
        private set
    var bossLife = -1
        private set
    var activeDef: MechanicDef? = null
        private set
    var instakillActive = false
        private set
    var lastHandler = ""
        private set
    val review = FightReview()

    private var activeAnim = -1
    private var startTick = 0L
    private var stepIndex = 0
    private var stepExecuted = false
    private var stepTick = 0L
    private var stepFiredAt = 0L
    private var stepWaitMillis = 0L
    private var stepArrivedTick: Long? = null

    private var addsActive = false
    private var poolsActive = false
    private var manifestationActive = false
    private var poolClearRequested = false

    private var lastLethalMoveTick = -99L
    private var lethalTarget: Point? = null
    private var sweepEscaped = false
    private val shadowSeen = HashMap<Point, Long>()

    private var lastPoolAbilityTick = -99L
    private var lastPoolTargetTick = -99L
    private var lastPoolSurgeTick = -99L
    private var poolTarget: Int? = null

    private var lastManifestAbilityTick = -99L
    private var lastManifestTargetTick = -99L
    private var manifestTarget: Int? = null

    private var nextExpelAt = 0L
    private var expelIndex = 0
    private var lastBombMoveTick = -1L
    private val lastFired = HashMap<Int, Long>()
    private var lastSeenAnim = -1

    private var homeTile: Point? = null
    private var homeAlt: Point? = null
    private var arenaCenter: Point? = null
    private var bossCenter: Point? = null
    private var lastHomeMoveTick = -99L

    @Volatile
    private var lastSiphonTick = -999L

    private val tick: Long get() = ServerTick.count

    private val handlers = listOf(
        Handler("Lethal ground", { true }, ::dodgeLethalGround),
        Handler("Tail sweep safety net", { true }, ::ensureClearOfSweep),
        Handler("Shadow energy", { true }, ::expelShadowEnergy),
        Handler("Boss mechanic", { scan.boss.found && activeDef != null }, ::runActive),
        Handler("Anima pools", ::addsMayMoveUs, ::clearAnimaPools),
        Handler("Shadow manifestation", ::addsMayMoveUs, ::killManifestation),
        Handler("Return home", { activeDef == null && !instakillActive }, ::returnHome),
    )

    val rotationOnHold: Boolean get() = poolsActive || manifestationActive || addsActive

    val status: String
        get() = when {
            instakillActive -> "Dodging lethal ground"
            addsActive -> "Expelling shadow energy"
            poolsActive -> "Clearing anima pools"
            manifestationActive -> "Killing shadow manifestation"
            else -> activeDef?.name ?: "On Raksha"
        }

    fun update(): Boolean {
        scan.refresh()
        val boss = scan.boss
        if (boss.found) bossCenter = Point(floor(boss.x).toInt(), floor(boss.y).toInt())
        arenaCenter = home() ?: bossCenter

        if (boss.found) {
            bossLife = boss.life
            review.noteBossLife(boss.life)
            val hpPhase = phaseForLife(boss.life)
            if (hpPhase > phase) {
                log("PHASE $hpPhase (boss ${boss.life} hp)")
                phase = hpPhase
            }
        }
        review.sampleHealth(tick, healthCurrent, RakshaIds.ANIM_NAMES[boss.anim] ?: if (boss.found) "auto/idle" else "boss unreadable")
        registerMechanic(boss)

        for (handler in handlers) {
            if (handler.applies() && handler.respond()) {
                lastHandler = handler.name
                return true
            }
        }
        lastHandler = ""
        return false
    }

    fun onChat(message: String) {
        if (message.contains(RakshaIds.SIPHON_CHAT, ignoreCase = true)) {
            lastSiphonTick = tick
            log("siphon announced: $message")
        }
    }

    fun requestPoolClear() {
        poolClearRequested = true
    }

    fun setPhase(next: Int) {
        if (next > phase) {
            log("PHASE $next (latched by event, boss $bossLife hp)")
            phase = next
        }
    }

    fun setHome(x: Int, y: Int) {
        homeTile = Point(x, y)
        homeAlt = Point(x + HOME_ALTERNATE_OFFSET_X, y)
        log("home set ($x, $y), alternate (${x + HOME_ALTERNATE_OFFSET_X}, $y)")
    }

    fun resetFight() {
        phase = 1
        bossLife = -1
        activeAnim = -1
        activeDef = null
        lastSeenAnim = -1
        lastFired.clear()
        addsActive = false
        poolsActive = false
        poolTarget = null
        poolClearRequested = false
        lastPoolSurgeTick = -99L
        manifestationActive = false
        manifestTarget = null
        instakillActive = false
        lethalTarget = null
        sweepEscaped = false
        shadowSeen.clear()
        expelIndex = 0
        arenaCenter = null
        bossCenter = null
        homeTile = null
        homeAlt = null
        lastSiphonTick = -999L
        review.reset()
        scan.invalidate()
    }

    fun reattackBoss(): Boolean {
        val attacked = scan.boss.npc?.interact("Attack") == true
        log("reattack Raksha${if (attacked) "" else " (failed)"}")
        return attacked
    }

    fun phaseForLife(life: Int): Int = when {
        life <= 0 -> phase
        life > settings.scaled(RakshaIds.PHASE2_HP_SOLO) -> 1
        life > settings.scaled(RakshaIds.PHASE3_HP_SOLO) -> 2
        life > settings.scaled(RakshaIds.PHASE4_HP_SOLO) -> 3
        else -> 4
    }

    private fun addsMayMoveUs() = activeDef?.exclusive != true && !instakillActive

    private fun isTargetingBoss() = !rotationOnHold

    private fun siphonImminent() = tick - lastSiphonTick < SIPHON_WINDOW_TICKS

    private fun poolClearInProgress() = poolsActive || poolClearRequested

    private fun arenaRadius(): Double = if (phase >= 4) PHASE4_ARENA_RADIUS else ARENA_RADIUS

    private fun canDive() = abilityReady("Dive") || abilityReady("Bladed Dive")

    private fun diveTo(p: Point) = diveToTile(p.x, p.y, scan.plane)

    private fun walk(p: Point) = walkToTile(p.x, p.y, scan.plane)

    private fun registerMechanic(boss: BossReading) {
        if (!boss.found) {
            if (activeDef != null) finish()
            return
        }
        val changed = boss.anim != lastSeenAnim
        lastSeenAnim = boss.anim
        if (!changed) return
        val def = MechanicDefinitions.forAnimation(boss.anim, phase) ?: return
        review.seen(def.name)
        if (suppressed(boss.anim, def)) return
        val active = activeDef
        if (active == null || def.priority >= active.priority) begin(boss.anim, def)
    }

    private fun suppressed(anim: Int, def: MechanicDef): Boolean {
        val last = lastFired[anim] ?: return false
        return tick - last < def.retriggerAfter
    }

    private fun begin(anim: Int, def: MechanicDef) {
        activeAnim = anim
        activeDef = def
        startTick = tick
        stepIndex = 0
        stepExecuted = false
        stepTick = 0L
        stepFiredAt = 0L
        stepArrivedTick = null
        lastBombMoveTick = -1L
        poolsActive = false
        lastFired[anim] = tick
        review.answered(def.name)
        log("mechanic started: ${def.name}")
    }

    private fun finish() {
        activeDef?.let { log("mechanic ended: ${it.name}") }
        activeAnim = -1
        activeDef = null
    }

    private fun shadowBoxes(): List<HazardBox> {
        val now = tick
        for (mark in scan.shadowMarks) shadowSeen[mark] = now
        shadowSeen.entries.removeIf { now - it.value > SHADOW_LINGER_TICKS }
        val clearance = min(escapeClearance(settings.shadowSafeRange, settings.shadowTriggerRange), MAX_SHADOW_CLEARANCE)
        return shadowSeen.keys.map { HazardBox.around(it.x.toDouble(), it.y.toDouble(), SHADOW_HALF, clearance) }
    }

    private fun instakillBoxes(): List<HazardBox> {
        val clearance = escapeClearance(settings.instakillSafeRange, settings.instakillTriggerRange)
        return scan.instakillMarks.map { HazardBox.around(it.x.toDouble(), it.y.toDouble(), 0.0, clearance) }
    }

    private fun poolBoxes(): List<HazardBox> {
        val player = scan.player
        return scan.pools
            .filter { distance(it.centerX, it.centerY, player.x, player.y) <= POOL_HAZARD_RANGE }
            .map { HazardBox.around(it.centerX, it.centerY, 0.0, POOL_AVOID_CLEARANCE) }
    }

    private fun bombBoxes(): List<HazardBox> = scan.bombMarks.map {
        HazardBox.around(it.x.toDouble(), it.y.toDouble(), BOMB_HALF, settings.bombEscapeDistance.toDouble())
    }

    private fun lethalHazards(): List<HazardBox> = shadowBoxes() + instakillBoxes()

    private fun allHazards(): List<HazardBox> {
        val hazards = lethalHazards().toMutableList()
        val center = bossCenter
        if (phase >= 4 && center != null)
            hazards += HazardBox.around(center.x.toDouble(), center.y.toDouble(), BOSS_FOOTPRINT_HALF, BOSS_CLEARANCE)
        hazards += poolBoxes()
        hazards += bombBoxes()
        return hazards
    }

    private fun walkAvoiding(target: Point, hazards: List<HazardBox>, urgent: Boolean): Boolean {
        val player = scan.player
        if (pathHazardCost(player.x, player.y, target.x, target.y, hazards) == 0) return walk(target)
        val stone = ArenaGeometry.steppingStone(player, target, hazards)
        if (stone == null) {
            if (!urgent) {
                log("no clean route - holding rather than walking through")
                return false
            }
            log("no clean route, but standing here is lethal - going direct")
            return walk(target)
        }
        log("routing around hazard via (${stone.x}, ${stone.y})")
        return walk(stone)
    }

    private fun findSafeTile(hazards: List<HazardBox>) =
        ArenaGeometry.findSafeTile(scan.player, hazards, arenaCenter, arenaRadius())

    private fun home(): Point? {
        if (phase >= 4 && bossCenter != null) {
            val center = bossCenter ?: return null
            return orbitTileAt(orbitNearestSlot(center.x + PHASE4_HOME_OFFSET_X, center.y))
        }
        val primary = homeTile ?: return null
        val hazards = allHazards()
        if (tileIsClear(primary.x, primary.y, hazards)) return primary
        val alt = homeAlt
        if (alt != null && tileIsClear(alt.x, alt.y, hazards)) return alt
        return primary
    }

    private fun orbitTileAt(slot: Int): Point? {
        val center = bossCenter ?: return null
        val offset = ArenaGeometry.ring(PHASE4_HOME_OFFSET_X)[ArenaGeometry.wrapSlot(slot) - 1]
        return Point(center.x + offset.x, center.y + offset.y)
    }

    private fun orbitNearestSlot(x: Int, y: Int): Int {
        val center = bossCenter ?: return 1
        val ring = ArenaGeometry.ring(PHASE4_HOME_OFFSET_X)
        return ring.indices.minBy { i ->
            val dx = center.x + ring[i].x - x
            val dy = center.y + ring[i].y - y
            dx * dx + dy * dy
        } + 1
    }

    private fun arcCost(from: Int, direction: Int, steps: Int, hazards: List<HazardBox>): Int {
        var cost = 0
        for (k in 1..steps) {
            val tile = orbitTileAt(from + direction * k) ?: return Int.MAX_VALUE
            if (hazards.any { it.distanceTo(tile.x.toDouble(), tile.y.toDouble()) < 1.0 }) cost++
        }
        return cost
    }

    private fun planOrbitMove(hazards: List<HazardBox>, preferredSlot: Int): Pair<Point?, Point?> {
        val player = scan.player
        if (bossCenter == null) return null to null
        var target: Point? = null
        var targetSlot = preferredSlot
        search@ for (offset in 0..ArenaGeometry.ORBIT_SLOTS / 2) {
            for (direction in intArrayOf(1, -1)) {
                val slot = preferredSlot + direction * offset
                val tile = orbitTileAt(slot) ?: continue
                if (tileIsClear(tile.x, tile.y, hazards)) {
                    target = tile
                    targetSlot = ArenaGeometry.wrapSlot(slot)
                    break@search
                }
            }
        }
        if (target == null) return null to null

        val current = orbitNearestSlot(player.x, player.y)
        if (targetSlot == current) {
            val dx = target.x - player.x
            val dy = target.y - player.y
            if (dx * dx + dy * dy <= 1) return null to target
            if (pathHazardCost(player.x, player.y, target.x, target.y, hazards) > 0) return null to target
            return target to target
        }

        var (steps, direction) = ArenaGeometry.ringDelta(current, targetSlot)
        val cost = arcCost(current, direction, steps, hazards)
        val otherSteps = ArenaGeometry.ORBIT_SLOTS - steps
        if (arcCost(current, -direction, otherSteps, hazards) < cost) {
            steps = otherSteps
            direction = -direction
        }
        return orbitTileAt(current + direction * min(steps, ORBIT_STEP_SLOTS)) to target
    }

    private fun dodgeLethalGround(): Boolean {
        val player = scan.player
        val hazards = ArrayList<HazardBox>()
        var nearestDanger = Double.MAX_VALUE
        var instakillNear = false

        for (box in instakillBoxes()) {
            hazards += box
            val d = box.distanceTo(player.x.toDouble(), player.y.toDouble())
            if (d <= settings.instakillTriggerRange) {
                instakillNear = true
                nearestDanger = min(nearestDanger, d)
            }
        }
        val shadowTrigger = min(settings.shadowTriggerRange, MAX_SHADOW_TRIGGER).toDouble()
        for (box in shadowBoxes()) {
            hazards += box
            val d = box.distanceTo(player.x.toDouble(), player.y.toDouble())
            if (d <= shadowTrigger) nearestDanger = min(nearestDanger, d)
        }

        if (nearestDanger == Double.MAX_VALUE) {
            instakillActive = false
            lethalTarget = null
            return false
        }
        if (!instakillActive) {
            instakillActive = true
            log("LETHAL GROUND ${"%.1f".format(nearestDanger)} tiles away - MOVING")
        }

        val castFreedom = instakillNear && abilityReady("Freedom") && castAbility("Freedom")
        val now = tick

        if (phase >= 4 && bossCenter != null) {
            val (hop, target) = planOrbitMove(hazards, orbitNearestSlot(player.x, player.y))
            if (hop != null) {
                if (now - lastLethalMoveTick >= INTERACT_TICKS) {
                    lastLethalMoveTick = now
                    lethalTarget = target
                    log("LETHAL GROUND - orbiting via (${hop.x}, ${hop.y})")
                    walk(hop)
                }
                return castFreedom
            }
        }

        var committed = lethalTarget?.takeIf { tileIsClear(it.x, it.y, hazards) }
        if (committed != null && distance(committed.x, committed.y, player.x, player.y) <= 1.0) committed = null
        if (committed == null) {
            committed = findSafeTile(hazards) ?: fleeFrom(hazards, player)
            lethalTarget = committed
            log("LETHAL GROUND dodge -> (${committed.x}, ${committed.y})")
        }
        if (now - lastLethalMoveTick >= INTERACT_TICKS) {
            lastLethalMoveTick = now
            walkAvoiding(committed, hazards, urgent = true)
        }
        return castFreedom
    }

    private fun fleeFrom(hazards: List<HazardBox>, player: Point): Point {
        val closest = hazards.minBy { it.distanceTo(player.x.toDouble(), player.y.toDouble()) }
        var dx = player.x - closest.centerX
        var dy = player.y - closest.centerY
        var length = hypot(dx, dy)
        if (length < 0.01) {
            dx = 1.0
            dy = 0.0
            length = 1.0
        }
        val reach = closest.clearance + 2
        return ArenaGeometry.clampTo(
            floor(player.x + dx / length * reach).toInt(),
            floor(player.y + dy / length * reach).toInt(),
            arenaCenter,
            arenaRadius(),
        )
    }

    private fun sweepClearance() =
        if (phase >= 4) MechanicDefinitions.TAIL_SWEEP_CLEARANCE_P4 else MechanicDefinitions.TAIL_SWEEP_CLEARANCE

    private fun clearOfSweepRadius(): Boolean {
        val center = bossCenter ?: return false
        val player = scan.player
        return distance(player.x, player.y, center.x, center.y) >= sweepClearance()
    }

    private fun ensureClearOfSweep(): Boolean {
        val boss = scan.boss
        if (!boss.found || boss.anim !in RakshaIds.SWEEP_ANIMS) {
            sweepEscaped = false
            return false
        }
        if (sweepEscaped) return false
        if (clearOfSweepRadius()) {
            sweepEscaped = true
            return false
        }
        val mover = activeDef?.sweepMover
        if (activeAnim in RakshaIds.SWEEP_ANIMS && mover != null && isTargetingBoss() && abilityReady(mover)) return false
        return escapeSweep(sweepClearance())
    }

    private fun escapeSweep(clearance: Int): Boolean {
        val center = bossCenter ?: return false
        val player = scan.player
        if (distance(player.x, player.y, center.x, center.y) >= clearance) return false

        val hazards = allHazards() + HazardBox.around(center.x.toDouble(), center.y.toDouble(), 0.0, clearance.toDouble())
        val target = pickSweepEscapeTile(clearance, hazards)
        if (target == null) {
            log("tail sweep: no safe tile outside the sweep - walking clear")
            walkClearOfBoss(clearance)
            return false
        }
        if (canDive()) {
            if (diveTo(target)) {
                log("tail sweep: DIVED clear to (${target.x}, ${target.y})")
                return true
            }
            log("tail sweep: dive to (${target.x}, ${target.y}) was rejected")
        }
        log("tail sweep: no Dive available - walking to (${target.x}, ${target.y})")
        walkAvoiding(target, hazards, urgent = true)
        return false
    }

    private fun pickSweepEscapeTile(clearance: Int, hazards: List<HazardBox>): Point? {
        val center = bossCenter ?: return null
        val player = scan.player
        val anchor = arenaCenter
        val add = scan.manifestations.firstOrNull()
        val biasX = add?.centerX ?: player.x.toDouble()
        val biasY = add?.centerY ?: player.y.toDouble()
        var best: Point? = null
        var bestCost = Int.MAX_VALUE
        var bestBias = Double.MAX_VALUE
        for (dx in -SWEEP_SEARCH_RADIUS..SWEEP_SEARCH_RADIUS) {
            for (dy in -SWEEP_SEARCH_RADIUS..SWEEP_SEARCH_RADIUS) {
                val tx = player.x + dx
                val ty = player.y + dy
                if (distance(tx, ty, center.x, center.y) < clearance) continue
                if (anchor != null && distance(tx, ty, anchor.x, anchor.y) > arenaRadius()) continue
                if (!tileIsClear(tx, ty, hazards)) continue
                val cost = pathHazardCost(player.x, player.y, tx, ty, hazards)
                val bias = distance(tx, ty, biasX, biasY)
                if (cost < bestCost || (cost == bestCost && bias < bestBias)) {
                    best = Point(tx, ty)
                    bestCost = cost
                    bestBias = bias
                }
            }
        }
        return best
    }

    private fun walkClearOfBoss(clearance: Int): Boolean {
        val center = bossCenter ?: return false
        val player = scan.player
        if (distance(player.x, player.y, center.x, center.y) >= clearance) return false
        val hazards = allHazards() + HazardBox.around(center.x.toDouble(), center.y.toDouble(), 0.0, clearance.toDouble())
        val target = findSafeTile(hazards) ?: return false
        return walkAvoiding(target, hazards, urgent = true)
    }

    private fun retreatFromBoss(tiles: Int): Boolean {
        val player = scan.player
        var dx = 1.0
        var dy = 0.0
        bossCenter?.let { center ->
            val ox = (player.x - center.x).toDouble()
            val oy = (player.y - center.y).toDouble()
            val length = hypot(ox, oy)
            if (length > 0.5) {
                dx = ox / length
                dy = oy / length
            }
        }
        val target = ArenaGeometry.clampTo(
            floor(player.x + dx * tiles + 0.5).toInt(),
            floor(player.y + dy * tiles + 0.5).toInt(),
            arenaCenter,
            arenaRadius(),
        )
        log("tail sweep: backing off $tiles tiles to (${target.x}, ${target.y})")
        walk(target)
        return false
    }

    private fun expelShadowEnergy(): Boolean {
        val present = scan.energies(fresh = addsActive)
        if (present.isNotEmpty()) {
            if (!addsActive) {
                addsActive = true
                log("Shadow energy up (${present.size}) - expelling")
            }
            val now = System.currentTimeMillis()
            if (now >= nextExpelAt) {
                nextExpelAt = now + gaussian(EXPEL_INTERVAL_MS, EXPEL_INTERVAL_VARIANCE)
                expelIndex = expelIndex % present.size + 1
                present[expelIndex - 1].interact("Expel")
            }
            return true
        }
        if (addsActive) {
            addsActive = false
            log("Shadow energy cleared - reattacking Raksha")
            reattackBoss()
            return true
        }
        return false
    }

    private fun runActive(): Boolean {
        val def = activeDef ?: return false
        val now = tick
        val elapsed = now - startTick

        val escapeClearance = def.escapeSweepWhenNotTargeting
        if (escapeClearance != null && !isTargetingBoss() && escapeSweep(escapeClearance)) return true

        return when (val response = def.response) {
            is Response.Instant -> {
                val used = abilityReady(response.ability) && castAbility(response.ability)
                log("${response.ability} ${if (used) "used" else "not available"}")
                finish()
                used
            }
            is Response.Sequence -> runSequence(response.steps, now)
            is Response.Sustained -> {
                if (elapsed >= response.durationTicks) {
                    finish()
                    return false
                }
                when (response.behaviour) {
                    Behaviour.DODGE_BOMBS -> if (!poolClearInProgress()) dodgeBombs()
                    Behaviour.BURN_DOME -> burnDome(now)
                }
                false
            }
        }
    }

    private fun runSequence(steps: List<SequenceStep>, now: Long): Boolean {
        val step = steps.getOrNull(stepIndex)
        if (step == null) {
            finish()
            return false
        }
        val arrived = stepArrivedTick ?: now.also { stepArrivedTick = it }
        val preDelay = if (step.delayWhenNotTargeting > 0 && !isTargetingBoss()) step.delayWhenNotTargeting else 0

        var used = false
        if (!stepExecuted && now - arrived >= preDelay) {
            used = runStep(step, stepIndex + 1, steps.size)
            stepExecuted = true
            stepTick = now
            stepFiredAt = System.currentTimeMillis()
            stepWaitMillis = step.waitMillis?.let { gaussian(it, STEP_MILLIS_VARIANCE).toLong() } ?: 0L
        }
        val ready = stepExecuted && if (step.waitMillis != null)
            System.currentTimeMillis() - stepFiredAt >= stepWaitMillis
        else
            now - stepTick >= step.waitTicks
        if (ready) {
            stepIndex++
            stepExecuted = false
            stepArrivedTick = null
            if (stepIndex >= steps.size) finish()
        }
        return used
    }

    private fun runStep(step: SequenceStep, index: Int, total: Int): Boolean {
        if (step.attackBoss) return reattackBoss()
        step.retreat?.let {
            log("step $index/$total: stepping back $it tiles")
            return retreatFromBoss(it)
        }
        val ability = step.ability ?: return false
        val walkFrom = step.walkFromBossWhenNotTargeting
        if (!isTargetingBoss() && walkFrom != null) {
            escapeSweep(walkFrom)
            log("step $index/$total: not on boss - escaping the sweep")
            return false
        }
        if (!abilityReady(ability)) {
            step.retreatWhenUnavailable?.let {
                log("$ability not available - retreating on foot instead")
                return retreatFromBoss(it)
            }
            log("$ability not available, skipping step")
            return false
        }
        val used = castAbility(ability)
        log("step $index/$total: $ability${if (used) "" else " (failed)"}")
        return used
    }

    private fun burnDome(now: Long) {
        if (now - lastPoolTargetTick >= INTERACT_TICKS) {
            lastPoolTargetTick = now
            reattackBoss()
        }
        if (scan.boss.anim !in RakshaIds.SWEEP_ANIMS) returnHome()
    }

    private fun dodgeBombs() {
        val hazards = bombBoxes() + shadowBoxes() + poolBoxes()
        if (hazards.isEmpty()) return
        val player = scan.player
        if (tileIsClear(player.x, player.y, hazards)) return
        val now = tick
        if (now - lastBombMoveTick < MechanicDefinitions.BOMB_MOVE_EVERY_TICKS) return
        lastBombMoveTick = now
        val target = findSafeTile(hazards)
        if (target == null) {
            log("no safe tile from bombs/shadows")
            return
        }
        log("bomb/shadow dodge -> (${target.x}, ${target.y}), avoiding ${hazards.size} hazard(s)")
        walkAvoiding(target, hazards, urgent = true)
    }

    private fun clearAnimaPools(): Boolean {
        if (settings.ignoreAnimaPools) {
            poolsActive = false
            poolClearRequested = false
            return false
        }
        if (phase != 3) {
            if (poolsActive) {
                poolsActive = false
                log("phase $phase ignores anima pools - dropping clear, back to Raksha")
                reattackBoss()
            }
            poolClearRequested = false
            return false
        }
        val threshold = settings.poolKillThreshold
        val skipBelow = settings.scaled(RakshaIds.POOL_SKIP_BELOW_HP_SOLO)
        if (bossLife in 1 until skipBelow) {
            if (poolsActive) {
                poolsActive = false
                log("boss at $bossLife hp (< $skipBelow) - dropping pools, pushing to phase 4")
                reattackBoss()
            }
            poolClearRequested = false
            return false
        }

        val pools = scan.pools
        if (pools.isEmpty()) {
            if (poolsActive) {
                poolsActive = false
                poolTarget = null
                log("anima pools cleared (0 left) - back to Raksha")
                reattackBoss()
            }
            poolClearRequested = false
            return false
        }
        if (poolsActive && pools.size <= threshold && !siphonImminent()) {
            poolsActive = false
            poolClearRequested = false
            poolTarget = null
            log("anima pools: ${pools.size} left (threshold $threshold) - back to Raksha")
            reattackBoss()
            return false
        }
        if (!poolsActive) {
            if (bossLife > settings.scaled(RakshaIds.POOL_START_BELOW_HP_SOLO)) return false
            val siphoning = siphonImminent()
            if (!poolClearRequested && !siphoning && pools.size <= threshold) return false
            log(
                "anima pools: ${pools.size} up (threshold $threshold${if (siphoning) ", SIPHONING" else ""}" +
                    "${if (poolClearRequested) ", ROTATION" else ""}) - clearing",
            )
        }
        poolsActive = true
        poolClearRequested = true

        val player = scan.player
        val distanceOf = { npc: NPC -> distance(npc.centerX, npc.centerY, player.x, player.y) }
        val nearest = pools.minBy(distanceOf)
        val current = pools.firstOrNull { it.serverIndex == poolTarget }
        val now = tick
        val staleClick = now - lastPoolTargetTick >= POOL_RECLICK_TICKS
        val needTarget = current == null || staleClick
        val target = if (needTarget) pickPoolTarget(pools, distanceOf) else current
        if (needTarget) {
            target.interact("Attack")
            poolTarget = target.serverIndex
            lastPoolTargetTick = now
        }

        val approachDistance = distanceOf(target)
        if (approachDistance > settings.poolDiveDistance && canDive()) {
            val tile = Point(floor(target.centerX).toInt(), floor(target.centerY).toInt())
            if (tileIsClear(tile.x, tile.y, lethalHazards())) {
                if (diveTo(tile)) {
                    log("dived to pool at (${tile.x}, ${tile.y}), ${"%.1f".format(approachDistance)} away")
                    return true
                }
            } else {
                log("skipped dive - destination is inside a hazard")
            }
        }
        if (approachDistance > POOL_SURGE_DISTANCE && poolTarget != null &&
            now - lastPoolTargetTick >= POOL_SURGE_FACE_TICKS &&
            now - lastPoolSurgeTick >= POOL_SURGE_REPEAT_TICKS &&
            abilityReady("Surge")
        ) {
            lastPoolSurgeTick = now
            if (castAbility("Surge")) {
                log("surged toward pool ${"%.1f".format(approachDistance)} away")
                return true
            }
        }
        if (settings.useRevolution) return true
        if (now - lastPoolAbilityTick < GCD_TICKS) return true
        val ability = POOL_DPS_ABILITIES.firstOrNull { abilityReady(it) } ?: return true
        log("pool DPS: $ability")
        castAbility(ability)
        lastPoolAbilityTick = now
        return true
    }

    private fun pickPoolTarget(pools: List<NPC>, distanceOf: (NPC) -> Double): NPC {
        var best = pools.first()
        var bestScore = -1
        var bestDistance = Double.MAX_VALUE
        for (pool in pools) {
            val d = distanceOf(pool)
            val score = pools.count { other ->
                other !== pool && distance(other.centerX, other.centerY, pool.centerX, pool.centerY) <= POOL_CLUSTER_RADIUS
            }
            val closer = d < bestDistance - POOL_DISTANCE_TIE
            val sameish = abs(d - bestDistance) <= POOL_DISTANCE_TIE
            if (bestScore < 0 || closer || (sameish && score > bestScore)) {
                best = pool
                bestScore = score
                bestDistance = d
            }
        }
        log("next pool: ${"%.1f".format(bestDistance)} away, $bestScore others within $POOL_CLUSTER_RADIUS tiles")
        return best
    }

    private fun killManifestation(): Boolean {
        val skipBelow = settings.scaled(RakshaIds.MANIFESTATION_SKIP_BELOW_HP_SOLO)
        if (bossLife in 1 until skipBelow) {
            if (manifestationActive) {
                manifestationActive = false
                manifestTarget = null
                log("boss at $bossLife hp (< $skipBelow) - ignoring manifestation, pushing to phase 4")
                reattackBoss()
            }
            return false
        }
        val target = scan.manifestations.firstOrNull()
        if (target == null) {
            if (manifestationActive) {
                manifestationActive = false
                manifestTarget = null
                log("Shadow manifestation dead - back to Raksha")
                reattackBoss()
            }
            return false
        }
        if (!manifestationActive) {
            manifestationActive = true
            log("Shadow manifestation up - killing fast")
        }
        val now = tick
        if (manifestTarget != target.serverIndex || now - lastManifestTargetTick >= MANIFEST_RECLICK_TICKS) {
            target.interact("Attack")
            manifestTarget = target.serverIndex
            lastManifestTargetTick = now
        }
        if (settings.useRevolution) return true
        if (now - lastManifestAbilityTick < GCD_TICKS) return true

        val haveSoul = Effect.RESIDUAL_SOUL.stacks >= 1
        val ability = when {
            haveSoul && abilityReady("Soul Strike") -> "Soul Strike"
            !haveSoul && abilityReady("Soul Sap") -> "Soul Sap"
            else -> MANIFESTATION_DPS_ABILITIES.firstOrNull { abilityReady(it) }
        } ?: return true
        log("manifestation: $ability")
        castAbility(ability)
        lastManifestAbilityTick = now
        return true
    }

    private fun returnHome(): Boolean {
        val player = scan.player
        val now = tick
        val hazards = allHazards()
        if (phase >= 4 && bossCenter != null) {
            returnToPhase4Home(player, now, hazards)
            return false
        }
        if (now - lastHomeMoveTick < HOME_RETURN_EVERY_TICKS) return false
        val home = home() ?: return false
        if (distance(home.x, home.y, player.x, player.y) <= HOME_RETURN_DISTANCE) return false
        if (!tileIsClear(home.x, home.y, hazards)) return false
        lastHomeMoveTick = now
        walkAvoiding(home, hazards, urgent = false)
        return false
    }

    private fun returnToPhase4Home(player: Point, now: Long, hazards: List<HazardBox>) {
        val home = home() ?: return
        val center = bossCenter ?: return
        val gap = distance(home.x, home.y, player.x, player.y)
        if (gap <= 1.0) return
        val pace = if (gap > HOME_HURRY_DISTANCE) 1 else HOME_RETURN_EVERY_TICKS
        if (now - lastHomeMoveTick < pace) return
        val homeClear = tileIsClear(home.x, home.y, hazards)

        if (homeClear && gap > HOME_DIVE_DISTANCE && canDive()) {
            lastHomeMoveTick = now
            if (diveTo(home)) {
                log("phase 4: dived home, ${"%.1f".format(gap)} tiles out")
                return
            }
        }
        if (homeClear && pathHazardCost(player.x, player.y, home.x, home.y, hazards) == 0) {
            lastHomeMoveTick = now
            walk(home)
            return
        }
        val homeSlot = orbitNearestSlot(center.x + PHASE4_HOME_OFFSET_X, center.y)
        val hop = planOrbitMove(hazards, homeSlot).first ?: return
        lastHomeMoveTick = now
        walk(hop)
    }

    private companion object {
        const val ARENA_RADIUS = 8.0
        const val PHASE4_ARENA_RADIUS = 10.0
        const val PHASE4_HOME_OFFSET_X = 4
        const val BOSS_FOOTPRINT_HALF = 2.0
        const val BOSS_CLEARANCE = 1.0
        const val HOME_ALTERNATE_OFFSET_X = 8
        const val HOME_RETURN_DISTANCE = 5.0
        const val HOME_RETURN_EVERY_TICKS = 3
        const val HOME_HURRY_DISTANCE = 3.0
        const val HOME_DIVE_DISTANCE = 6.0
        const val ORBIT_STEP_SLOTS = 2
        const val SWEEP_SEARCH_RADIUS = 12

        const val SHADOW_HALF = 2.0
        const val SHADOW_LINGER_TICKS = 3
        const val MAX_SHADOW_CLEARANCE = 3.0
        const val MAX_SHADOW_TRIGGER = 2
        const val BOMB_HALF = 1.0
        const val POOL_HAZARD_RANGE = 25.0
        const val POOL_AVOID_CLEARANCE = 2.0

        const val GCD_TICKS = 3
        const val INTERACT_TICKS = 1
        const val POOL_RECLICK_TICKS = 5
        const val MANIFEST_RECLICK_TICKS = 5
        const val POOL_SURGE_FACE_TICKS = 2
        const val POOL_SURGE_REPEAT_TICKS = 5
        const val POOL_SURGE_DISTANCE = 6.0
        const val POOL_CLUSTER_RADIUS = 3.0
        const val POOL_DISTANCE_TIE = 1.0
        const val SIPHON_WINDOW_TICKS = 30

        const val EXPEL_INTERVAL_MS = 90L
        const val EXPEL_INTERVAL_VARIANCE = 25L
        const val STEP_MILLIS_VARIANCE = 150

        val POOL_DPS_ABILITIES = listOf("Threads of Fate", "Bloat", "Blood Siphon", "Soul Sap", "Touch of Death", "Basic Attack")
        val MANIFESTATION_DPS_ABILITIES = listOf("Touch of Death", "Soul Sap", "Basic Attack")
    }
}
