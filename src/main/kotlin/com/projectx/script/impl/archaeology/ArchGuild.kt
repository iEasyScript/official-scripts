package com.projectx.script.impl.archaeology

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.MakeX
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.hasActiveMakeXProgress
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.localPlayer
import com.projectx.script.api.makeX

/**
 * Everything done at the Archaeology Guild: restoring what has been dug up, emptying the soil box into
 * materials, and standing through a qualification ceremony.
 *
 * Restoration is the biggest single lever on a qualification. It is worth far more experience than digging,
 * and it is the only thing that moves the restored count, so a trip home pays for itself the moment the
 * backpack has artefacts in it.
 */

/**
 * Restores every damaged artefact in the backpack.
 *
 * The workbench opens the game's ordinary make interface, so the engine's own MakeX support drives it: pick
 * the recipe by the restored artefact's name, switching to its category when it is not on the open tab, and
 * let it run. Recipes the materials will not cover simply refuse, and those artefacts stay in the backpack
 * for the next trip rather than the run getting stuck on them.
 */
internal suspend fun Script.restoreArtefacts(): Boolean {
    if (!inventory.hasItem(*ArchData.damagedArtefactIds)) return true
    if (!openWorkbench()) return false

    var restoredAny = false
    while (inventory.hasItem(*ArchData.damagedArtefactIds)) {
        if (hasActiveMakeXProgress) {
            delayUntil(MAKE_TIMEOUT, pollingDelayMillis = 400) { !hasActiveMakeXProgress || !MakeX.isOpen }
            continue
        }
        // Skipped artefacts are passed over, not re-picked: re-picking the same one is how this loop
        // would spin forever on an artefact the materials will not cover.
        val damaged = inventory.firstOrNull { item ->
            item.id !in skipped && ArchData.restorationForDamaged(item.id) != null
        } ?: break
        val recipe = ArchData.restorationForDamaged(damaged.id) ?: break
        if (recipe.level > ArchProgress.level) {
            skipped += damaged.id
            continue
        }
        val category = ArchData.categoryOf(recipe.restoredId)
        val started = makeX({ it.equals(recipe.name, ignoreCase = true) }, { category != null && it == category })
        if (!started) {
            // Not offered: the materials are short, or it is filed somewhere this account cannot see yet.
            skipped += damaged.id
            delay(500, 200)
            continue
        }
        restoredAny = true
        delayUntil(MAKE_TIMEOUT, pollingDelayMillis = 400) { !hasActiveMakeXProgress || !MakeX.isOpen }
        delay(600, 250)
        if (!MakeX.isOpen && !openWorkbench()) break
    }
    skipped.clear()
    closeMakeX()
    return restoredAny
}

/** Damaged artefacts this trip could not restore, so the loop moves on instead of retrying them forever. */
private val skipped = mutableSetOf<Int>()

/**
 * Opens a workbench, preferring one already in reach.
 *
 * Every dig site camp has its own bench and material storage, so a backpack of artefacts is usually restored
 * without going home at all - which saves two teleports per trip and keeps the run at the hotspot.
 */
private suspend fun Script.openWorkbench(): Boolean {
    if (MakeX.isOpen && MakeX.hasPanel) return true
    var bench = findWorkbench()
    if (bench == null) {
        if (!ArchTravel.atGuild() && !teleportToGuild()) return false
        if (!walkNear(ArchIds.GUILD_WORKBENCH_TILE)) return false
        bench = findWorkbench()
    }
    if (bench == null) return false
    if (!bench.interact("Restore")) return false
    delayUntil(INTERFACE_TIMEOUT) { MakeX.isOpen && MakeX.hasPanel }
    return MakeX.isOpen && MakeX.hasPanel
}

// Resolved id as well as the raw one: a multi-loc's placed id is an unnamed shell that the data
// never records, so matching the raw id alone silently finds nothing.
private fun findWorkbench() = findClosestObject(FACILITY_RANGE) { obj ->
    ArchIds.WORKBENCH_OBJECTS.any { it == obj.visibleTypeId || it == obj.id } && obj.hasOption("Restore")
}

private fun findMaterialStorage() = findClosestObject(FACILITY_RANGE) { obj ->
    ArchIds.MATERIAL_STORAGE_OBJECTS.any { it == obj.visibleTypeId || it == obj.id } &&
        (obj.hasOption("Deposit all") || obj.hasOption("Store"))
}

private suspend fun Script.closeMakeX() {
    if (!MakeX.isOpen) return
    IFSlot(MakeX.PARENT, MAKEX_CLOSE, -1).click(1)
    delayUntil(INTERFACE_TIMEOUT) { !MakeX.isOpen }
}

/**
 * Empties the soil box into materials at a screening mesh and puts the result into storage.
 *
 * Soil is the bulk of what a dig produces and materials are what restoration spends, so this is the step
 * that keeps the workbench supplied. It is done on the trip home rather than mid-dig, where it would cost
 * a walk for nothing.
 */
internal suspend fun Script.screenAndStore(): Boolean {
    if (!inventory.hasItem(ArchIds.SOIL_BOX)) return false
    if (!ArchTravel.atGuild() && !teleportToGuild()) return false
    if (!walkNear(ArchIds.GUILD_SCREENING_TILE)) return false
    val mesh = findClosestObject(FACILITY_RANGE) { obj ->
        ArchIds.SCREENING_OBJECTS.any { it == obj.visibleTypeId || it == obj.id } && obj.hasOption("Screen")
    }
    if (mesh != null && mesh.interact("Screen")) {
        // Screening runs a batch; it ends when the box is empty or the backpack has no room left.
        delayUntil(SCREEN_TIMEOUT, pollingDelayMillis = 400) { inventory.isFull || !localPlayer.isAnimating }
        DigState.screened()
        delay(700, 300)
    }
    return storeMaterials()
}

/** Puts every material in the backpack into the guild's material storage container. */
internal suspend fun Script.storeMaterials(): Boolean {
    if (!inventory.hasItem(*ArchIds.MATERIALS)) return true
    var container = findMaterialStorage()
    if (container == null) {
        if (!ArchTravel.atGuild() && !teleportToGuild()) return false
        if (!walkNear(ArchIds.GUILD_STORAGE_TILE)) return false
        container = findMaterialStorage()
    }
    if (container == null) return false
    val before = inventory.count(*ArchIds.MATERIALS)
    val option = if (container.hasOption("Deposit all")) "Deposit all" else "Store"
    if (!container.interact(option)) return false
    delayUntil(INTERFACE_TIMEOUT) {
        inventory.count(*ArchIds.MATERIALS) < before || materialStorageOpen()
    }
    if (materialStorageOpen()) {
        IFSlot(ArchIds.MATERIAL_STORAGE_INTERFACE, ArchIds.MATERIAL_STORAGE_DEPOSIT, -1).click(1)
        delayUntil(DEPOSIT_TIMEOUT) { inventory.count(*ArchIds.MATERIALS) < before }
        IFSlot(ArchIds.MATERIAL_STORAGE_INTERFACE, ArchIds.MATERIAL_STORAGE_CLOSE, -1).click(1)
        delayUntil(INTERFACE_TIMEOUT) { !materialStorageOpen() }
    }
    delay(600, 250)
    return inventory.count(*ArchIds.MATERIALS) < before
}

private fun materialStorageOpen() =
    interfaces.getComponent(ArchIds.MATERIAL_STORAGE_INTERFACE, 0)?.visible == true

/**
 * Attends the qualification ceremony with the Acting Guildmaster.
 *
 * This is the step that actually awards the rank: the requirements can all be met and the qualification
 * still not be held until the certificate has been handed over. The certificate interface going up, and the
 * qualification varbit moving, are what confirm it - not the dialogue having been clicked through.
 */
internal suspend fun Script.attendCeremony(): Boolean {
    val before = ArchProgress.qualificationRank
    if (!ArchTravel.atGuild() && !teleportToGuild()) return false
    if (!walkNear(ArchIds.GUILDMASTER_TILE)) return false
    val guildmaster = findClosestNPC(ArchIds.GUILDMASTER_NPC, FACILITY_RANGE) ?: return false

    if (!isDialogOpen() && !certificateOpen()) {
        if (!guildmaster.interact("Talk-to")) return false
        delayUntil(INTERFACE_TIMEOUT) { isDialogOpen() || certificateOpen() }
    }

    var guard = 0
    while (guard++ < DIALOGUE_STEPS && ArchProgress.qualificationRank == before) {
        if (certificateOpen()) {
            delay(1400, 500)
            IFSlot(ArchIds.QUALIFICATION_INTERFACE, ArchIds.QUALIFICATION_CLOSE, -1).click(1)
            delayUntil(INTERFACE_TIMEOUT) { !certificateOpen() }
            continue
        }
        if (!isDialogOpen()) break
        CEREMONY_OPTIONS.firstOrNull { continueDialogueContaining(it) }
        delay(900, 380)
    }
    delayUntil(CEREMONY_TIMEOUT) { ArchProgress.qualificationRank > before }
    return ArchProgress.qualificationRank > before
}

private fun certificateOpen() =
    interfaces.getComponent(ArchIds.QUALIFICATION_INTERFACE, 0)?.visible == true

/** The things the Guildmaster offers on the way to a ceremony; the first that matches is taken. */
private val CEREMONY_OPTIONS = listOf(
    "qualification",
    "ceremony",
    "Yes",
    "ready",
    "continue",
)

private const val FACILITY_RANGE = 20
private const val INTERFACE_TIMEOUT = 8_000L
private const val DEPOSIT_TIMEOUT = 8_000L
private const val MAKE_TIMEOUT = 180_000L
private const val SCREEN_TIMEOUT = 120_000L
private const val CEREMONY_TIMEOUT = 15_000L
private const val DIALOGUE_STEPS = 24
private const val MAKEX_CLOSE = 34
