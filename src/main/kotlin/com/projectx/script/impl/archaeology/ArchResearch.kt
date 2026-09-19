package com.projectx.script.impl.archaeology

import com.projectx.game.interfaces.IFSlot
import com.projectx.script.Script
import com.projectx.script.api.equipment
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.varps

/**
 * Keeping a research contract running.
 *
 * Research is the one requirement that cannot be hurried: it is banked in real time, twenty-four hours of it
 * for Associate and a full week for Guildmaster, and it accrues whether or not anyone is logged in. So the
 * only thing that matters is that a contract is always out, and that is checked before anything else each
 * time the script is at the guild. Nothing ever waits on it.
 *
 * A contract needs a team, and researchers are hired with chronotes. This will dispatch a team that has
 * already been hired; it will not spend the account's currency hiring one. If there is no team it says so
 * once and the rest of the run carries on, because every other requirement is still making progress.
 */
internal suspend fun Script.dispatchResearch(): Boolean {
    if (researchRunning()) return true
    if (!researchWorthRetrying()) return false
    if (!openResearchManagement()) {
        backOff()
        warnOnce("could not open research management - open the Archaeology journal's research tab once by hand")
        return false
    }

    val before = varps.getVarBit(ArchIds.RESEARCH_CONTRACT_STATE)
    // Pick the first research the team is allowed to take, then commit it.
    IFSlot(ArchIds.RESEARCH_INTERFACE, RESEARCH_LIST_CONTROL, 0).click(1)
    delay(700, 300)
    IFSlot(ArchIds.RESEARCH_INTERFACE, RESEARCH_START_BUTTON, -1).click(1)
    delayUntil(CONFIRM_TIMEOUT) { varps.getVarBit(ArchIds.RESEARCH_CONTRACT_STATE) != before }

    val started = varps.getVarBit(ArchIds.RESEARCH_CONTRACT_STATE) != before
    closeResearchManagement()
    if (started) {
        println("[Archaeology] Research contract sent out")
        warned = false
        retryAfter = 0
    } else {
        backOff()
        warnOnce("no research team to send out - hire researchers at the guild and it will start using them")
    }
    return started
}

/** A contract is out when the game's own contract state is anything but idle. */
internal fun researchRunning(): Boolean = varps.getVarBit(ArchIds.RESEARCH_CONTRACT_STATE) != 0

/**
 * Whether a dispatch is worth attempting again.
 *
 * Without a hired team every attempt fails the same way, and retrying each pass would be the click-spam this
 * script exists to avoid. So a failure stands the attempt down for a while, and the rest of the run gets on
 * with digging - which is also how a team eventually gets paid for.
 */
internal fun researchWorthRetrying(): Boolean = System.currentTimeMillis() >= retryAfter

private fun backOff() {
    retryAfter = System.currentTimeMillis() + RETRY_DELAY_MILLIS
}

private var retryAfter = 0L

private fun researchOpen() =
    interfaces.getComponent(ArchIds.RESEARCH_INTERFACE, 0)?.visible == true

/**
 * Opens research management, by the guild's research notes when they are in reach and otherwise through the
 * journal, which carries the same tab.
 */
private suspend fun Script.openResearchManagement(): Boolean {
    if (researchOpen()) return true

    val notes = findClosestObject(FACILITY_RANGE) {
        it.id == RESEARCH_NOTES_OBJECT && it.hasOption("Manage")
    }
    if (notes != null && notes.interact("Manage")) {
        delayUntil(INTERFACE_TIMEOUT) { researchOpen() }
        if (researchOpen()) return true
    }

    val opened = inventory.clickItem(ArchIds.JOURNAL, "Read") || equipment.clickItem(ArchIds.JOURNAL, "Read")
    if (!opened) return false
    delayUntil(INTERFACE_TIMEOUT) { researchOpen() }
    return researchOpen()
}

private suspend fun Script.closeResearchManagement() {
    if (!researchOpen()) return
    IFSlot(ArchIds.RESEARCH_INTERFACE, ArchIds.RESEARCH_CLOSE, -1).click(1)
    delayUntil(INTERFACE_TIMEOUT) { !researchOpen() }
}

/** Said once, not every pass, so a guild without a research team does not fill the log. */
private var warned = false

private fun warnOnce(message: String) {
    if (warned) return
    warned = true
    println("[Archaeology] $message")
}

private const val RESEARCH_NOTES_OBJECT = 116437
private const val RESEARCH_LIST_CONTROL = 71
private const val RESEARCH_START_BUTTON = 195
private const val FACILITY_RANGE = 20
private const val INTERFACE_TIMEOUT = 8_000L
private const val CONFIRM_TIMEOUT = 8_000L

/** How long a failed dispatch stands down for. Research is measured in days; minutes cost nothing. */
private const val RETRY_DELAY_MILLIS = 10 * 60 * 1000L
