package com.projectx.script.impl.archaeology

import com.projectx.script.api.getRealLevel
import com.projectx.script.api.varps
import org.projectx.core.game.skill.Skill

/**
 * The five Archaeology qualifications and what each one asks for.
 *
 * The numbers come from the achievements the game itself checks - Archaeology n, The Discoverer, The
 * Restorer, Collectors Assemble, Mystery Solver and The Researcher - so a qualification the script believes
 * is ready is one the Guildmaster will actually award.
 */
enum class Qualification(
    val rank: Int,
    val title: String,
    val level: Int,
    val excavated: Int,
    val restored: Int,
    val collections: Int,
    val mysteries: Int,
    val researchHours: Int,
) {
    INTERN(1, "Intern", 1, 0, 0, 0, 0, 0),
    ASSISTANT(2, "Assistant", 40, 25, 25, 1, 1, 0),
    ASSOCIATE(3, "Associate", 70, 250, 250, 5, 10, 24),
    PROFESSOR(4, "Professor", 90, 500, 500, 20, 15, 72),
    GUILDMASTER(5, "Guildmaster", 99, 1000, 1000, 25, 20, 168);

    companion object {
        fun ofRank(rank: Int): Qualification? = entries.firstOrNull { it.rank == rank }
    }
}

/** One outstanding requirement, for the overlay and for deciding what to do next. */
data class Requirement(val name: String, val have: Int, val need: Int) {
    val met get() = have >= need
    val remaining get() = (need - have).coerceAtLeast(0)
}

/**
 * Everything the script knows about where the account stands, read fresh from the player's own vars.
 *
 * This is the whole of the script's memory of progress. Nothing about it is carried between runs in fields
 * or in a file of its own, because the game already records all of it: stopping mid-dig and starting again
 * tomorrow reads the same numbers back and carries on from them. It is also why the script cannot
 * double-count - the counters it reads are the ones the achievements are judged on. The only thing kept on
 * disk is the way back to each hotspot, in [ArchTravel], which is a route rather than progress.
 */
object ArchProgress {

    /** Game ticks per hour, which is the unit research time is banked in. */
    private const val TICKS_PER_HOUR = 6_000

    val level: Int get() = getRealLevel(Skill.ARCHAEOLOGY)

    /**
     * Whether the Archaeology tutorial has been finished. Intern is awarded for that alone, and the script
     * cannot do a tutorial, so this is the one thing it checks before deciding it has anything to work on.
     */
    val tutorialDone: Boolean get() = varps.getVarBit(ArchIds.TUTORIAL) > 0

    val qualification: Qualification? get() = Qualification.ofRank(varps.getVarBit(ArchIds.QUALIFICATION))

    val qualificationRank: Int get() = varps.getVarBit(ArchIds.QUALIFICATION)

    val excavated: Int get() = varps.getVarBit(ArchIds.DISCOVERY_TOTAL)

    val restored: Int get() = varps.getVarBit(ArchIds.RESTORATION_TOTAL)

    /** Mysteries solved: every bit of the two completion varps is one mystery, so this is two reads. */
    val mysteries: Int
        get() = ArchIds.MYSTERY_VARPS.sumOf { Integer.bitCount(varps.getVar(it)) }

    val researchHours: Int get() = varps.getVar(ArchIds.RESEARCH_TIME_VARP) / TICKS_PER_HOUR

    val collectionsCompleted: Int
        get() = ArchCollections.all.count { varps.getVarBit(it.completeVarbit) == 1 }

    /** The qualification being worked towards, or null once Guildmaster has been awarded. */
    val target: Qualification? get() = Qualification.entries.firstOrNull { it.rank > qualificationRank }

    /** True when a qualification has been earned and only the ceremony is left. */
    val ceremonyOwed: Boolean
        get() = ArchIds.QUALIFICATION_OWED.any { varps.getVarBit(it) == 1 }

    /** Every requirement for [target], met or not, in the order the overlay shows them. */
    fun requirements(): List<Requirement> {
        val goal = target ?: return emptyList()
        return listOf(
            Requirement("Archaeology level", level, goal.level),
            Requirement("Artefacts excavated", excavated, goal.excavated),
            Requirement("Artefacts restored", restored, goal.restored),
            Requirement("Collections", collectionsCompleted, goal.collections),
            Requirement("Mysteries", mysteries, goal.mysteries),
            Requirement("Research hours", researchHours, goal.researchHours),
        ).filter { it.need > 0 }
    }

    fun outstanding(): List<Requirement> = requirements().filterNot { it.met }

    /** True when everything [target] asks for is done and the ceremony is all that remains. */
    fun readyForCeremony(): Boolean = target != null && outstanding().isEmpty()

    fun siteUnlocked(mapIndex: Int): Boolean =
        ArchIds.SITE_UNLOCKED[mapIndex]?.let { varps.getVarBit(it) == 1 } ?: false

    /**
     * Whether a hotspot has been found before. The game sets this the first time you dig one, so it is a
     * reliable "this worked last time" - but never a veto, because every hotspot reads 0 until first use.
     */
    fun hotspotKnown(hotspot: Hotspot): Boolean = varps.getVarBit(hotspot.stateVarbit) > 0

    /** Collections not yet finished, closest to done first - the cheapest one to go and finish. */
    fun unfinishedCollections(): List<CollectorCollection> =
        ArchCollections.all
            .filter { varps.getVarBit(it.completeVarbit) != 1 }
            .sortedByDescending { collection -> collection.slots.count { donated(it) } }

    fun donated(slot: CollectionSlot): Boolean = varps.getVarBit(slot.donatedVarbit) == 1

    /** The artefacts [collection] still wants, as restored item ids. */
    fun outstandingSlots(collection: CollectorCollection): List<CollectionSlot> =
        collection.slots.filterNot { donated(it) }
}
