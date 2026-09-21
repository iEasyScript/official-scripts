package com.projectx.script.impl.archaeology

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.varps
import world.gregs.voidps.type.Tile

/**
 * Handing restored artefacts to their collectors.
 *
 * A finished collection is worth far more towards a qualification than the artefacts that went into it -
 * Guildmaster wants twenty-five of them - so this runs ahead of ordinary restoring whenever the backpack is
 * carrying something a collector is waiting for.
 *
 * Velucia stands outside the guild and takes thirty of the seventy-eight collections, so most of this never
 * involves leaving the campus. A collector the wiki has no map pin for has no tile in [ArchCollections], and
 * the script names them and moves on rather than guessing a coordinate and walking somewhere wrong.
 *
 * The hand-in itself is a conversation - the collector board is only a reading list, with no button to press
 * - so the outcome is judged on the game's own donated bits moving, never on the dialogue having been
 * clicked through. An artefact a collector will not take therefore costs one attempt, not a loop.
 */
internal suspend fun Script.handInCollections(): Boolean {
    val holding = ArchPlanner.heldCollectionArtefacts()
    if (holding.isEmpty()) return true

    val collection = collectionToVisit(holding) ?: run {
        reportUnreachable()
        return false
    }

    if (!reachCollector(collection)) {
        println("[Archaeology] Could not reach ${collection.collector} at ${collection.where}")
        return false
    }

    val wasComplete = varps.getVarBit(collection.completeVarbit) == 1
    val before = ArchProgress.outstandingSlots(collection).size
    donateTo(collection)

    if (!wasComplete && varps.getVarBit(collection.completeVarbit) == 1)
        println("[Archaeology] Completed ${collection.collector}'s collection ${collection.index}")
    return ArchProgress.outstandingSlots(collection).size < before
}

/** The nearest unfinished collection this run can get to and is carrying something for. */
private fun collectionToVisit(holding: List<CollectionSlot>): CollectorCollection? {
    val wanted = holding.map { it.donatedVarbit }.toSet()
    return ArchProgress.unfinishedCollections()
        .filter { it.spot != null }
        .firstOrNull { collection -> collection.slots.any { it.donatedVarbit in wanted } }
}

private fun reportUnreachable() {
    val stuck = ArchProgress.unfinishedCollections()
        .firstOrNull { c -> ArchProgress.outstandingSlots(c).any { inventory.hasItem(it.restoredId) } }
    println(
        "[Archaeology] Carrying artefacts for ${stuck?.collector ?: "a collector"} " +
            "(${stuck?.where ?: "location unknown"}), who this script has no map pin for. " +
            "Hand those in yourself; everything else carries on.",
    )
}

private suspend fun Script.reachCollector(collection: CollectorCollection): Boolean {
    if (findClosestNPC(collection.collectorNpcId, COLLECTOR_RANGE) != null) return true
    val spot = collection.spot ?: return false
    walkNear(Tile.of(spot.x, spot.y, spot.plane))
    return findClosestNPC(collection.collectorNpcId, COLLECTOR_RANGE) != null
}

/**
 * Hands a collector what they are owed, through their collection log.
 *
 * This is an interface, not a conversation. Talking to a collector opens `collection_log`, and until
 * something is clicked in it nothing has been given away - which is why watching for a dialogue found
 * none and the run stood there having opened a window it never used.
 *
 * Two clicks do it. Collectors keep several collections - four, for Sir Atcha - and the list is in their
 * own order, so the collection is chosen by its place in that list before anything is handed over. Then
 * the footer button gives them everything in the backpack that the chosen collection is still missing.
 *
 * The outcome is judged on the game's own donated bits moving, never on the clicking having happened, so
 * an artefact a collector will not take costs one attempt rather than a loop.
 */
private suspend fun Script.donateTo(collection: CollectorCollection) {
    val collector = findClosestNPC(collection.collectorNpcId, COLLECTOR_RANGE) ?: return
    if (!interfaces.isOpen(COLLECTION_LOG)) {
        if (!collector.interact("Archaeology collection") && !collector.interact("Talk-to")) return
        delayUntil(INTERFACE_TIMEOUT) { interfaces.isOpen(COLLECTION_LOG) }
        if (!interfaces.isOpen(COLLECTION_LOG)) return
    }

    // The list counts from zero and a collection's index counts from one.
    IFSlot(COLLECTION_LOG, COLLECTION_LIST, collection.index - 1).click(1)
    delay(700, 300)

    var outstanding = ArchProgress.outstandingSlots(collection).size
    var attempts = 0
    while (outstanding > 0 && attempts++ < DONATE_ATTEMPTS) {
        IFSlot(COLLECTION_LOG, DONATE_BUTTON, 0).click(1)
        delayUntil(DONATE_TIMEOUT) { ArchProgress.outstandingSlots(collection).size < outstanding }
        val now = ArchProgress.outstandingSlots(collection).size
        // Nothing moved, so there is nothing here it wants. Pressing again would only repeat that.
        if (now == outstanding) break
        outstanding = now
    }

    IFSlot(COLLECTION_LOG, CLOSE_BUTTON, -1).click(1)
    delayUntil(INTERFACE_TIMEOUT) { !interfaces.isOpen(COLLECTION_LOG) }
}

/**
 * The collection log, by the names the client gives its own components.
 *
 * Read off the engine's action log while handing in a Rod of Asclepius by hand, which is also what
 * confirmed the list slot is the collection's index less one: the bit that moved was
 * `collection_sir_atcha_03_arch_rod_of_asclepius`, from picking the third row down.
 */
private const val COLLECTION_LOG = 656

/** `collection_log:collection_list_control_layer` - one row per collection. */
private const val COLLECTION_LIST = 31

/** `collection_log:info_footer_control_layer` - hands over what the chosen collection is missing. */
private const val DONATE_BUTTON = 25

/** `collection_log:mainmodal_window_close_button`. */
private const val CLOSE_BUTTON = 27

private const val DONATE_ATTEMPTS = 6
private const val DONATE_TIMEOUT = 5_000L

private const val COLLECTOR_RANGE = 20
private const val INTERFACE_TIMEOUT = 8_000L
