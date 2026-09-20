package com.projectx.script.impl.archaeology

import com.projectx.script.Script
import com.projectx.script.api.captureSerenSpirit
import com.projectx.script.api.checkPorter
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.findSerenSpirit
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.timeSpriteElsewhere
import com.projectx.script.api.timeSpriteTile
import world.gregs.voidps.type.Tile
import com.projectx.util.gaussian

/** Why an excavation pass ended, which is what the main loop switches on. */
internal enum class DigResult { WORKING, BACKPACK_FULL, UNREACHABLE }

/** The little state a dig carries between passes. */
internal object DigState {
    /**
     * Latched when a Fill moved no soil, so the box is not clicked every pass until it has been screened.
     * Screening clears it, which is the only thing that can make room in the box.
     */
    var soilBoxFull: Boolean = false

    fun screened() {
        soilBoxFull = false
    }
}

/**
 * Digging a hotspot, and keeping the backpack fit to carry on digging.
 *
 * Only damaged artefacts are worth carrying home: they are what the excavated and restored counts are made
 * of, and what collections are made of. Soil and materials are bulk, so they are pushed out of the backpack
 * as they arrive - materials to storage through the site's own cart or a porter, soil into the soil box - and
 * the run keeps its slots for the things a qualification actually counts.
 */
internal suspend fun Script.digAt(hotspot: Hotspot): DigResult {
    if (captureSerenSpirit()) return DigResult.WORKING
    checkPorter()

    // Travel reports failure when it got to the site but could not see the hotspot, and a hotspot nobody has
    // dug yet is invisible to that check, so an uncover is tried before the planner is told to give up on it.
    if (ArchTravel.findHotspot(hotspot) == null && !travelToHotspot(hotspot)) {
        return if (uncoverNearby(hotspot)) DigResult.WORKING else DigResult.UNREACHABLE
    }
    // A time sprite settles on one patch and is worth far more than carrying on where we are, so when one is up
    // the dig aims at the hotspot nearest it rather than the one nearest us.
    val sprite = timeSpriteTile()
    val target = sprite?.let { ArchTravel.findHotspotNear(hotspot, it) }
        ?: ArchTravel.findHotspot(hotspot)
        ?: return if (uncoverNearby(hotspot)) DigResult.WORKING else DigResult.UNREACHABLE

    if (clearBackpack(hotspot)) return DigResult.WORKING
    if (inventory.isFull) return DigResult.BACKPACK_FULL

    if (!target.interact("Excavate")) {
        delay(700, 260)
        return DigResult.WORKING
    }
    if (sprite != null) println("[Archaeology] Digging ${hotspot.name} on the time sprite at ${sprite.x},${sprite.y}")
    ArchTravel.remember(target)
    watchDig(hotspot, target.tile)
    delay(320, 140)
    return DigResult.WORKING
}

/**
 * Excavation carries on by itself, so this only watches for a reason to stop early: a full backpack, enough
 * soil to be worth boxing, a Seren spirit to catch, a time sprite settling on a different patch, or the player
 * going idle because the hotspot depleted.
 */
private suspend fun Script.watchDig(hotspot: Hotspot, digging: Tile) {
    var lastBusy = System.currentTimeMillis()
    delayUntil(DIG_TIMEOUT, pollingDelayMillis = 300) {
        val now = System.currentTimeMillis()
        if (localPlayer.isAnimating || localPlayer.isMoving) lastBusy = now
        inventory.isFull ||
            findSerenSpirit() != null ||
            timeSpriteElsewhere(digging) ||
            (!DigState.soilBoxFull && inventory.count(hotspot.soilId) >= SOIL_BEFORE_FILLING) ||
            now - lastBusy > gaussian(IDLE_GRACE_MILLIS, 500L)
    }
}

/**
 * Makes room without going anywhere: soil into the box, materials to storage through the site's cart.
 * Returns true when it did something, so the caller yields rather than clicking the hotspot in the same pass.
 */
private suspend fun Script.clearBackpack(hotspot: Hotspot): Boolean {
    if (!DigState.soilBoxFull &&
        inventory.count(hotspot.soilId) >= SOIL_BEFORE_FILLING &&
        fillSoilBox()
    ) return true
    if (inventory.freeSlots <= MATERIAL_HEADROOM && depositMaterialsAtCart()) return true
    return false
}

/**
 * Uncovers a buried hotspot standing in the scene, which is what makes the wanted one findable at all.
 *
 * A hotspot this account has never dug is still the dig site's soil, so the ids the script looks for match
 * nothing until it has been uncovered once. The soil's own shell still names what it will become, so the one
 * we came for is picked out of its neighbours where it can be, and the next pass looks for it again.
 *
 * Returns true when something was uncovered, so the caller reports WORKING rather than giving up on a hotspot
 * that is simply still buried.
 */
private suspend fun Script.uncoverNearby(hotspot: Hotspot): Boolean {
    // The wanted one if its shell gives it away, otherwise whichever is nearest: uncovering is a one-off
    // experience reward and reveals a hotspot the planner can use later, so a neighbour is never wasted.
    val buried = ArchTravel.findBuriedHotspot(hotspot) ?: ArchTravel.findAnyBuriedHotspot() ?: return false
    println("[Archaeology] Uncovering ${buried.name()} at ${buried.tile.x},${buried.tile.y}")
    if (!walkNear(buried.tile)) return false
    if (!buried.interact(ArchTravel.UNCOVER)) return false
    delayUntil(UNCOVER_TIMEOUT) { !buried.exists && !localPlayer.isAnimating }
    delay(900, 400)
    return true
}

/** Tips the backpack's soil into the soil box. A full box moves nothing, which is what latches the flag. */
internal suspend fun Script.fillSoilBox(): Boolean {
    if (!inventory.hasItem(ArchIds.SOIL_BOX) || !inventory.hasItem(*ArchData.soilIds)) return false
    val before = inventory.count(*ArchData.soilIds)
    if (!inventory.clickItem(ArchIds.SOIL_BOX, "Fill")) return false
    delayUntil(FILL_TIMEOUT) { inventory.count(*ArchData.soilIds) < before }
    val moved = inventory.count(*ArchData.soilIds) < before
    DigState.soilBoxFull = !moved
    delay(420, 180)
    return moved
}

/**
 * Deposits materials into material storage through the dig site's materials cart, which saves the walk home.
 * A script leaning on a sign of the porter alone stalls the moment the porter runs out; this does not.
 */
internal suspend fun Script.depositMaterialsAtCart(): Boolean {
    if (!inventory.hasItem(*ArchIds.MATERIALS)) return false
    // Resolved id as well as the raw one, for the same reason hotspots need it: a cart may be a multi-loc.
    val cart = findClosestObject(CART_RANGE) { obj ->
        ArchIds.MATERIALS_CART_OBJECTS.any { it == obj.visibleTypeId || it == obj.id } &&
            obj.hasOption("Deposit materials")
    } ?: return false
    val before = inventory.count(*ArchIds.MATERIALS)
    if (!cart.interact("Deposit materials")) return false
    delayUntil(DEPOSIT_TIMEOUT) { inventory.count(*ArchIds.MATERIALS) < before }
    delay(600, 240)
    return inventory.count(*ArchIds.MATERIALS) < before
}

/**
 * Gets to [hotspot]: straight back to a tile that has worked before, or out to its dig site and a sweep for
 * the scenery.
 *
 * Returns false when the site would not travel - one the account has not unlocked - or when the sweep found
 * nothing, which is usually a hotspot behind a door the script has not been through. Either way the planner
 * leaves this hotspot alone for the rest of the run instead of retrying it every pass.
 */
private suspend fun Script.travelToHotspot(hotspot: Hotspot): Boolean {
    if (returnToHotspot(hotspot)) return true

    val site = ArchData.digSite(hotspot.siteIndex)
    if (site == null) return false
    if (!fastTravelTo(site)) {
        println("[Archaeology] ${site.name} would not fast travel; skipping ${hotspot.name}")
        return false
    }
    if (exploreForHotspot(hotspot) != null) return true
    println("[Archaeology] Found no way to ${hotspot.name} from ${site.name}; skipping it for this run")
    return false
}

private const val DIG_TIMEOUT = 180_000L
private const val IDLE_GRACE_MILLIS = 3_000L
private const val FILL_TIMEOUT = 4_000L
private const val DEPOSIT_TIMEOUT = 8_000L
private const val CART_RANGE = 25
private const val UNCOVER_TIMEOUT = 12_000L

/** Soil is tipped into the box in batches, so the box is not clicked after every single find. */
private const val SOIL_BEFORE_FILLING = 6

/** Slots kept clear of materials, so a full backpack means artefacts rather than bulk. */
private const val MATERIAL_HEADROOM = 6
