package com.projectx.script.impl.archaeology

import com.projectx.script.api.inventory

/** What the script has decided to do next, and the one sentence the overlay shows for it. */
internal data class Plan(val job: Job, val reason: String, val hotspot: Hotspot? = null)

internal enum class Job { CEREMONY, RESEARCH, RESTORE, COLLECT, EXCAVATE, IDLE }

/**
 * Chooses the next job from the requirements that are still outstanding.
 *
 * The ordering is not arbitrary. Research is real time the account spends whether or not it is logged in, so
 * a contract is always got running first and the run never waits on it. The ceremony is what actually awards
 * a qualification, so it jumps the queue the moment everything else is done. After that the cheapest
 * outstanding requirement wins: artefacts already dug are restored before more are dug, and artefacts a
 * collection wants are handed in before anything else is restored, because a collection is worth far more
 * towards a qualification than one more restoration count.
 */
internal object ArchPlanner {

    /** Hotspots that could not be reached this run, so the planner stops choosing them. */
    private val unreachable = mutableSetOf<String>()

    fun markUnreachable(hotspot: Hotspot) {
        unreachable += hotspot.name
        ArchTravel.forget(hotspot)
    }

    fun reachableAgain() = unreachable.clear()

    fun isUnreachable(hotspot: Hotspot) = hotspot.name in unreachable

    fun plan(researchIdle: Boolean, allowResearch: Boolean, allowCollections: Boolean): Plan {
        val goal = ArchProgress.target
            ?: return Plan(Job.IDLE, "Guildmaster earned - nothing left to qualify for")

        if (ArchProgress.ceremonyOwed || ArchProgress.readyForCeremony())
            return Plan(Job.CEREMONY, "Collecting the ${goal.title} qualification")

        // Research banks real time, so it is always worth having one running before anything else starts.
        // A dispatch that just failed is stood down, so a guild with no hired team does not stall the run.
        if (allowResearch && researchIdle && researchWorthRetrying() &&
            ArchProgress.researchHours < goal.researchHours
        ) return Plan(Job.RESEARCH, "Sending the research team out")

        val outstanding = ArchProgress.outstanding().associateBy { it.name }
        val needsRestorations = outstanding.containsKey("Artefacts restored")
        val needsCollections = allowCollections && outstanding.containsKey("Collections")

        if (needsCollections && heldCollectionArtefacts().isNotEmpty())
            return Plan(Job.COLLECT, "Handing in restored artefacts to a collector")

        if (inventory.hasItem(*ArchData.damagedArtefactIds) && (needsRestorations || needsCollections))
            return Plan(Job.RESTORE, "Restoring the artefacts already dug up")

        val hotspot = chooseHotspot(needsCollections)
            ?: return Plan(Job.IDLE, "No hotspot this account can reach at level ${ArchProgress.level}")
        return Plan(Job.EXCAVATE, excavateReason(hotspot, outstanding.keys), hotspot)
    }

    private fun excavateReason(hotspot: Hotspot, outstanding: Set<String>) = when {
        "Collections" in outstanding && collectionTarget(hotspot) != null ->
            "Digging ${hotspot.name} for ${collectionTarget(hotspot)?.collector}'s collection"
        "Archaeology level" in outstanding -> "Digging ${hotspot.name} for the level"
        else -> "Digging ${hotspot.name} for artefacts"
    }

    /** Restored artefacts in the backpack that some unfinished collection still wants. */
    fun heldCollectionArtefacts(): List<CollectionSlot> =
        ArchProgress.unfinishedCollections()
            .flatMap { ArchProgress.outstandingSlots(it) }
            .filter { inventory.hasItem(it.restoredId) }

    /** The collection, if any, that [hotspot] is currently feeding. */
    fun collectionTarget(hotspot: Hotspot): CollectorCollection? =
        ArchProgress.unfinishedCollections().firstOrNull { collection ->
            ArchProgress.outstandingSlots(collection).any { slot ->
                val damaged = ArchData.restorationForRestored(slot.restoredId)?.damagedId ?: return@any false
                hotspot.damagedArtefacts.contains(damaged)
            }
        }

    /**
     * The hotspot to dig.
     *
     * With collections outstanding the choice is led by them: a hotspot that yields an artefact some
     * collection is still missing is worth far more than a slightly better experience rate. Otherwise the
     * highest hotspot the account can use wins, which is also the fastest for levels and for the excavated
     * and restored counts.
     */
    fun chooseHotspot(preferCollections: Boolean): Hotspot? {
        currentSite = ArchTravel.currentSiteIndex()
        val candidates = ArchData.hotspots.filter { usable(it) }
        if (candidates.isEmpty()) return null

        if (preferCollections) {
            val wanted = ArchProgress.unfinishedCollections()
                .flatMap { ArchProgress.outstandingSlots(it) }
                .mapNotNull { ArchData.restorationForRestored(it.restoredId)?.damagedId }
                .toSet()
            candidates
                .filter { hotspot -> hotspot.damagedArtefacts.any { it in wanted } }
                .maxByOrNull { score(it) }
                ?.let { return it }
        }
        return candidates.maxByOrNull { score(it) }
    }

    /**
     * Prefers a hotspot the script is already standing next to, then one it knows the way to, then the
     * highest level. The bonuses dwarf the level on purpose: a hotspot in reach is worth more than a
     * slightly better one behind two teleports, and this is what stops the run touring the world.
     */
    private fun score(hotspot: Hotspot): Int {
        val here = if (hotspot.siteIndex == currentSite) HERE_BONUS else 0
        val known = if (ArchProgress.hotspotKnown(hotspot)) KNOWN_BONUS else 0
        val learned = if (ArchTravel.learnedTile(hotspot) != null) LEARNED_BONUS else 0
        return hotspot.level + here + known + learned
    }

    /** Cached for the length of one choice, because reading the scene per candidate would be wasteful. */
    private var currentSite = 0

    private fun usable(hotspot: Hotspot): Boolean =
        hotspot.level <= ArchProgress.level &&
            hotspot.subSiteLevel <= ArchProgress.level &&
            hotspot.objectIds.isNotEmpty() &&
            !isUnreachable(hotspot) &&
            ArchProgress.siteUnlocked(hotspot.siteIndex)

    private const val KNOWN_BONUS = 200
    private const val LEARNED_BONUS = 400
    private const val HERE_BONUS = 800
}
