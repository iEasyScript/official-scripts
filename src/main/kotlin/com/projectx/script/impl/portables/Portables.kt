package com.projectx.script.impl.portables

import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.MakeX
import com.projectx.script.api.SkillTracker
import com.projectx.script.api.bankOpen
import com.projectx.script.api.captureSerenSpirit
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.getXp
import com.projectx.script.api.inventory
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.isPlayerBusy
import com.projectx.script.api.loadBankPreset
import com.projectx.game.input.Key
import com.projectx.script.api.clickKey
import com.projectx.script.api.makeXConfirm
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.section
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar

/**
 * Works a portable skilling station until the materials run out.
 *
 * Portables are placed by players and stand for a while, so the whole run happens in one spot: fill from a
 * bank preset, feed the station, repeat. There is no walking and no route - if the station and a bank are
 * not both in reach, this is not the script for the job.
 *
 * What is being made is decided by the bank preset and by whatever the make window was last set to, not by
 * configuration here. The preset carries the materials and the window remembers the recipe, so this only
 * has to keep the two meeting. That is why there is no list of item ids to fill in.
 */
@ScriptDescription(
    name = "Portables",
    version = "1.2.0",
    author = "Cryptic",
    description = "Works a portable station - workbench, fletcher, range, well, crafter or brazier - " +
        "restocking from a bank preset. Stand where the station and a bank are both in reach.",
    category = ScriptCategory.CRAFTING,
)
class Portables : Script(), ConfigurableScript {

    private val station = EnumConfigItem(
        name = "Station",
        description = "The portable to work at.",
        enumValues = Portable.entries.toTypedArray(),
        initialValue = Portable.CRAFTER,
    )

    private val preset = IntConfigItem(
        name = "Bank preset",
        description = "The preset to load when out of materials. 0 loads whichever preset was used last.",
        initialValue = 0,
        min = 0,
        max = 9,
    )

    private val stopWhenOut = BooleanConfigItem(
        name = "Stop when the bank runs dry",
        description = "Stop once a preset no longer restocks anything, rather than waiting for more.",
        initialValue = true,
    )

    private val tracker = SkillTracker()

    private var status = "Starting"
    private var lastXpGainMillis = 0L
    private var lastXpTotal = 0
    private var lastStationSeenMillis = 0L

    override fun onStart() {
        tracker.reset()
        val now = System.currentTimeMillis()
        lastXpGainMillis = now
        lastStationSeenMillis = now
        lastXpTotal = getXp(station.value.skill)
        println("[Portables] Working a ${station.value} for ${station.value.skill}")
    }

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (!isPlayerBusy() && captureSerenSpirit()) return tracker.add("Seren spirits")

        noteProgress()
        if (giveUp()) return

        // The window, once open, is the only honest answer to whether there is anything left to make. A
        // backpack holding what was just made is not an empty one, so its contents cannot be asked - which
        // is how a run came to finish a batch of potions and go straight back to the well for another.
        if (MakeX.isOpen && !MakeX.inProgress) {
            if (MakeX.maxQuantity <= 0) {
                status = "Out of materials"
                closeMakeWindow()
                return restock()
            }
            status = "Starting the job"
            makeXConfirm()
            return delay(900, 400)
        }

        if (isPlayerBusy() || MakeX.inProgress) {
            status = "Working"
            return delay(700, 300)
        }

        // A brazier puts up no window to ask, so for that one an empty backpack is the signal.
        if (bankOpen || inventory.isEmpty) return restock()

        useStation()
    }

    /**
     * Sends the player at the station.
     *
     * A station that is in the scene but offers nothing we recognise is named in the log rather than
     * clicked at. The option list is the part most likely to be wrong - a portable's left-click option is
     * configurable, so the wording varies - and a run that says what it saw can be corrected from one line.
     */
    private suspend fun useStation() {
        val portable = station.value
        val obj = findClosestObject(SEARCH_RANGE) { portable.matches(it) }
        if (obj == null) {
            status = "No ${portable.toString().lowercase()} in reach"
            return delay(1200, 400)
        }
        lastStationSeenMillis = System.currentTimeMillis()

        val option = portable.optionOn(obj)
        if (option == null) {
            println(
                "[Portables] ${obj.name()} (${obj.id}/${obj.visibleTypeId}) offers none of " +
                    "${portable.options}; the one it does offer needs adding",
            )
            status = "Station offers no option I know"
            return delay(1500, 500)
        }

        status = "Using the ${portable.toString().lowercase()}"
        if (!obj.interact(option)) return delay(800, 300)

        if (portable.usesMakeInterface) {
            delayUntil(INTERFACE_TIMEOUT) { MakeX.isOpen }
        } else {
            // Nothing opens for a brazier; the player simply starts burning where they stand.
            delayUntil(INTERFACE_TIMEOUT) { isPlayerBusy() }
        }
        delay(600, 250)
    }

    /**
     * Fills the backpack, or decides there is nothing left to fill it with.
     *
     * A bank chest loads the last preset from its own menu, without the bank window ever opening, which is
     * both quicker and what a player does. Only a numbered preset needs the window, so that is the one case
     * that opens it.
     */
    private suspend fun restock() {
        closeMakeWindow()
        val chest = findClosestObject(SEARCH_RANGE) { obj -> BANK_OPTIONS.any { obj.hasOption(it) } }
        if (chest == null && !bankOpen) {
            status = "No bank in reach"
            return delay(1500, 500)
        }

        val before = inventory.usedSlots
        if (preset.value == 0 && chest != null && chest.hasOption(LOAD_LAST_PRESET)) {
            status = "Loading last preset"
            if (!chest.interact(LOAD_LAST_PRESET)) return delay(800, 300)
            delayUntil(INTERFACE_TIMEOUT) { inventory.usedSlots != before }
        } else {
            if (!bankOpen) {
                status = "Opening the bank"
                val option = chest?.let { obj -> BANK_OPTIONS.firstOrNull { obj.hasOption(it) } }
                    ?: return delay(1200, 400)
                if (!chest.interact(option)) return delay(800, 300)
                delayUntil(INTERFACE_TIMEOUT) { bankOpen }
                if (!bankOpen) return
            }
            status = "Loading preset ${preset.value}"
            loadBankPreset(preset.value)
            delayUntil(INTERFACE_TIMEOUT) { !bankOpen }
        }
        delay(700, 300)

        if (inventory.isEmpty) {
            // Nothing came back, so the bank has nothing left to give.
            if (stopWhenOut.value) {
                println("[Portables] The preset restocked nothing; stopping")
                stop()
            } else {
                status = "Waiting for materials"
                delay(5000, 2000)
            }
            return
        }
        // Restocking is progress even though it earns nothing, so the idle clock starts again here. Without
        // that, a run told to wait for materials would be stopped by the very timer meant to catch a run
        // that is stuck.
        lastXpGainMillis = System.currentTimeMillis()
        tracker.add("Loads")
    }

    /**
     * Shuts the make window, which otherwise sits over the bank chest and a preset will not load beneath it.
     *
     * Escape rather than the close button: the window's close component is not where the other make-style
     * interfaces keep theirs, and a key that always works beats a number that has to be right.
     */
    private suspend fun closeMakeWindow() {
        if (!MakeX.isOpen) return
        clickKey(Key.ESCAPE)
        delayUntil(INTERFACE_TIMEOUT) { !MakeX.isOpen }
    }

    /** Keeps the clock honest about when the account last actually gained something. */
    private fun noteProgress() {
        val xp = getXp(station.value.skill)
        if (xp > lastXpTotal) {
            lastXpTotal = xp
            lastXpGainMillis = System.currentTimeMillis()
        }
    }

    /**
     * Whether to stop.
     *
     * Two silences mean different things. No experience while a station is standing there is the run being
     * broken. No station at all is somebody having picked theirs up, which is worth waiting out for longer
     * because another usually appears - so that clock is the generous one, and it also holds off the first.
     */
    private fun giveUp(): Boolean {
        val now = System.currentTimeMillis()
        val sinceStation = now - lastStationSeenMillis
        if (sinceStation > NO_STATION_TIMEOUT) {
            println("[Portables] No ${station.value} for ${NO_STATION_TIMEOUT / 60_000} minutes; stopping")
            stop()
            return true
        }
        if (now - lastXpGainMillis > NO_PROGRESS_TIMEOUT && sinceStation < NO_PROGRESS_TIMEOUT) {
            println("[Portables] No ${station.value.skill} experience in ${NO_PROGRESS_TIMEOUT / 1000}s; stopping")
            stop()
            return true
        }
        return false
    }

    override fun render() {
        val portable = station.value
        ImGuiDsl.window("Portables") {
            section("Station")
            text("$portable  -  ${portable.skill}")
            text("Preset: ${if (preset.value > 0) preset.value.toString() else "last used"}")
            separator()
            section("Progress")
            text("Status: $status")
            text("Loads: ${tracker.countOf("Loads")}")
            text("XP/hr: ${tracker.xpPerHour(portable.skill)}")
            xpProgressBar(portable.skill)
        }
    }

    override fun onStop() =
        println("[Portables] Stopped after ${tracker.countOf("Loads")} loads at a ${station.value}")

    private companion object {
        const val SEARCH_RANGE = 12
        const val INTERFACE_TIMEOUT = 6_000L

        /** Long enough that a station being replaced does not end the run. */
        const val NO_STATION_TIMEOUT = 5 * 60_000L

        /** Short, because a station that is present and giving nothing is broken rather than slow. */
        const val NO_PROGRESS_TIMEOUT = 90_000L

        /** What the game calls loading whichever preset was used last. */
        const val LAST_PRESET = 0


        /** What a bank chest calls filling the backpack from the preset you last used, in one click. */
        const val LOAD_LAST_PRESET = "Load Last Preset from"

        /** How to open the bank proper, which only a numbered preset needs. */
        val BANK_OPTIONS = listOf(LOAD_LAST_PRESET, "Use", "Bank", "Open")
    }
}
