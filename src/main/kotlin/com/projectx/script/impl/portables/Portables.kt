package com.projectx.script.impl.portables

import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigVisibilityProvider
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
import com.projectx.script.api.closeBank
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
 * Which of a station's jobs to do - a crafter cuts gems, tans leather and fires clay as well as crafting - is
 * chosen here. What is being made within that job is decided by the bank preset and by whatever the make
 * window was last set to. The preset carries the materials and the window remembers the recipe, so this only
 * has to keep the two meeting. That is why there is no list of item ids to fill in.
 */
@ScriptDescription(
    name = "Portables",
    version = "1.5.1",
    author = "Cryptic",
    description = "Works a portable station - workbench, fletcher, range, well, crafter or brazier - at any " +
        "of its jobs, restocking from a bank preset. Stand where the station and a bank are both in reach.",
    category = ScriptCategory.CRAFTING,
)
class Portables : Script(), ConfigurableScript, ConfigVisibilityProvider {

    private val station = EnumConfigItem(
        name = "Station",
        description = "The portable to work at.",
        enumValues = Portable.entries.toTypedArray(),
        initialValue = Portable.CRAFTER,
    )

    private val fletcherJob = jobConfig(Portable.FLETCHER)
    private val crafterJob = jobConfig(Portable.CRAFTER)
    private val brazierJob = jobConfig(Portable.BRAZIER)

    /** The job picker for each station that has more than one job; the rest have nothing to choose. */
    private val jobConfigs = mapOf(
        Portable.FLETCHER to fletcherJob,
        Portable.CRAFTER to crafterJob,
        Portable.BRAZIER to brazierJob,
    )

    private val job: PortableJob
        get() = jobConfigs[station.value]?.value ?: station.value.jobs.first()

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

    /**
     * What the last restock put in the backpack, which is what this run counts as materials.
     *
     * Learned rather than configured. The preset is the only thing that knows what is being made, so what
     * it hands over is taken to be the materials, and they are gone when none of them is left. That is what
     * lets a finished batch go straight to the bank instead of opening the station to be told so.
     */
    private var materials: Set<Int> = emptySet()

    /**
     * Whether this load's batch has run: the game's progress window was seen counting it down.
     *
     * A batch makes everything it can in one go, so once that window closes the load is done and the bank is
     * next - even with materials still in the backpack, which is what a perk that saves ingredients leaves.
     * Going back to the station for those few made the round uneven: station, make, station again, bank.
     */
    private var batchRan = false

    private var lastXpGainMillis = 0L
    private var lastXpTotal = 0
    private var lastStationSeenMillis = 0L

    override fun onStart() {
        tracker.reset()
        val now = System.currentTimeMillis()
        lastXpGainMillis = now
        lastStationSeenMillis = now
        lastXpTotal = getXp(job.skill)
        materials = inventory.map { it.id }.toSet()
        batchRan = false
        println("[Portables] ${job} at a ${station.value} for ${job.skill}")
    }

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        // Not mid-batch: clicking a spirit stops the batch, and it waits out the trip to the bank well enough.
        if (!isPlayerBusy() && !MakeX.inProgress && captureSerenSpirit()) return tracker.add("Seren spirits")

        noteProgress()
        if (giveUp()) return

        if (MakeX.isOpen && !MakeX.inProgress) {
            if (MakeX.maxQuantity <= 0) {
                status = "Out of materials"
                closeMakeWindow()
                return restock()
            }
            status = "Starting the job"
            makeXConfirm()
            return delay(400, 400)
        }

        if (MakeX.inProgress) {
            batchRan = true
            status = "Working"
            return delay(300, 300)
        }

        // Before the busy check: the last item's animation can outlast the window by a moment, and the bank
        // is the next stop either way.
        if (batchRan) {
            status = "Batch done"
            return restock()
        }

        if (isPlayerBusy()) {
            status = "Working"
            return delay(300, 300)
        }

        if (bankOpen) return restock()
        if (outOfMaterials()) return restock()

        useStation()
    }

    /**
     * Sends the player at the station.
     *
     * A station that is in the scene but does not offer the chosen job is named in the log rather than
     * clicked at. The wording is the part most likely to be wrong, and a run that says what it saw can be
     * corrected from one line - which is how the brazier's "Add logs" was found.
     */
    private suspend fun useStation() {
        val portable = station.value
        val job = job
        val obj = findClosestObject(SEARCH_RANGE) { portable.matches(it) }
        if (obj == null) {
            status = "No ${portable.toString().lowercase()} in reach"
            return delay(1200, 400)
        }
        lastStationSeenMillis = System.currentTimeMillis()

        if (!obj.hasOption(job.option)) {
            println(
                "[Portables] ${obj.name()} (${obj.id}/${obj.visibleTypeId}) does not offer " +
                    "\"${job.option}\"; its wording needs correcting",
            )
            status = "Station offers no \"${job.option}\""
            return delay(1500, 500)
        }

        status = "${job}: using the ${portable.toString().lowercase()}"
        if (!obj.interact(job.option)) return delay(800, 300)

        if (job.opensMakeWindow) {
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
        }

        // A preset arrives in one go, so the backpack changing is the whole of the signal and there is
        // nothing left to wait out after it. Waiting on the bank window closing instead, and then pausing
        // on top of that, was most of the time a load took.
        delayUntil(RESTOCK_TIMEOUT, pollingDelayMillis = RESTOCK_POLL) { inventory.usedSlots != before }
        if (bankOpen) closeBank()

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
        materials = inventory.map { it.id }.toSet()
        batchRan = false
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

    /**
     * Whether the materials are gone.
     *
     * An empty backpack always counts, which is what a brazier leaves behind and the only signal it gives.
     * Otherwise it is the items the preset handed over that matter: what a job produces lands in the same
     * backpack, so a full one says nothing about whether there is anything left to work with.
     */
    private fun outOfMaterials(): Boolean {
        if (inventory.isEmpty) return true
        if (materials.isEmpty()) return false
        return materials.none { inventory.count(it) > 0 }
    }

    /** Keeps the clock honest about when the account last actually gained something. */
    private fun noteProgress() {
        val xp = getXp(job.skill)
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
            println("[Portables] No ${job.skill} experience in ${NO_PROGRESS_TIMEOUT / 1000}s; stopping")
            stop()
            return true
        }
        return false
    }

    /** Only the selected station's job picker is shown; the others would be choices that do nothing. */
    override fun isConfigItemVisible(fieldName: String, item: ConfigItem<*>): Boolean {
        jobConfigs.forEach { (portable, config) -> if (config === item) return portable == station.value }
        return true
    }

    private fun jobConfig(portable: Portable) = EnumConfigItem(
        name = "$portable job",
        description = "Which of the ${portable.toString().lowercase()}'s options to use.",
        enumValues = portable.jobs.toTypedArray(),
        initialValue = portable.jobs.first(),
    )

    override fun render() {
        val portable = station.value
        val job = job
        ImGuiDsl.window("Portables") {
            section("Station")
            text("$portable  -  $job (${job.skill})")
            text("Preset: ${if (preset.value > 0) preset.value.toString() else "last used"}")
            separator()
            section("Progress")
            text("Status: $status")
            text("Loads: ${tracker.countOf("Loads")}")
            text("XP/hr: ${tracker.xpPerHour(job.skill)}")
            xpProgressBar(job.skill)
        }
    }

    override fun onStop() =
        println("[Portables] Stopped after ${tracker.countOf("Loads")} loads of $job at a ${station.value}")

    private companion object {
        const val SEARCH_RANGE = 12
        const val INTERFACE_TIMEOUT = 6_000L

        /** How long a preset gets to land, and how closely it is watched for - it arrives in one tick. */
        const val RESTOCK_TIMEOUT = 5_000L
        const val RESTOCK_POLL = 60

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
