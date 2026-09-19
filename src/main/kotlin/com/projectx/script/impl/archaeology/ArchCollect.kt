package com.projectx.script.impl.archaeology

import com.projectx.script.Script
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
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
 * Talks the collector through taking what they are owed.
 *
 * The conversation is walked one option at a time, preferring anything that reads like handing artefacts
 * over, and it stops the moment a donated bit moves or the dialogue runs out. The step budget is what stops
 * an unfamiliar conversation turning into an endless click.
 */
private suspend fun Script.donateTo(collection: CollectorCollection) {
    val collector = findClosestNPC(collection.collectorNpcId, COLLECTOR_RANGE) ?: return
    if (!isDialogOpen()) {
        val opened = collector.interact("Archaeology collection") || collector.interact("Talk-to")
        if (!opened) return
        delayUntil(INTERFACE_TIMEOUT) { isDialogOpen() }
    }

    var steps = 0
    var outstanding = ArchProgress.outstandingSlots(collection).size
    while (isDialogOpen() && steps++ < DIALOGUE_STEPS) {
        val chose = DONATE_OPTIONS.any { option ->
            dialogueOptions.keys.any { it.contains(option, ignoreCase = true) } &&
                continueDialogueContaining(option)
        }
        if (!chose) continueDialogueContaining("")
        delay(820, 340)

        val now = ArchProgress.outstandingSlots(collection).size
        if (now < outstanding) {
            outstanding = now
            steps = 0
            if (now == 0) break
        }
    }
    delay(500, 200)
}

/**
 * The wording collectors use to take artefacts, best match first. The donated bits are what confirm the
 * result, so a phrase that has since been reworded costs an attempt rather than a wrong hand-in.
 */
private val DONATE_OPTIONS = listOf(
    "Give",
    "Donate",
    "Hand over",
    "collection",
    "artefact",
    "Yes",
)

private const val COLLECTOR_RANGE = 20
private const val INTERFACE_TIMEOUT = 8_000L
private const val DIALOGUE_STEPS = 20
