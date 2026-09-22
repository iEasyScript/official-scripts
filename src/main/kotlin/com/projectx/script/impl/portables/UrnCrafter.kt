package com.projectx.script.impl.portables

import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.MakeX
import com.projectx.script.api.SkillTracker
import com.projectx.script.api.bank
import com.projectx.script.api.bankOpen
import com.projectx.script.api.captureSerenSpirit
import com.projectx.script.api.closeBank
import com.projectx.script.api.continueDialogueContaining
import com.projectx.script.api.depositAllInventory
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.getXp
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.isPlayerBusy
import com.projectx.script.api.loadBankPreset
import com.projectx.script.api.withdrawBankItem
import com.projectx.script.api.makeX
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.section
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import org.projectx.core.game.skill.Skill

/**
 * Makes urns at a portable crafter.
 *
 * An urn is made twice: soft clay is moulded into an unfired urn, then unfired urns are fired into the
 * finished thing. Both passes happen at the same crafter, so a run is a cycle - withdraw clay, mould a
 * backpack of them, fire that backpack, bank the results, again.
 *
 * Which pass to make next is read off the backpack rather than remembered. A backpack of unfired urns is
 * fired, a backpack of clay is moulded, anything else goes to the bank. That way a run picked up halfway
 * through, or interrupted and restarted, carries on from wherever it actually is.
 */
@ScriptDescription(
    name = "Urn Crafter",
    version = "1.0.0",
    author = "Cryptic",
    description = "Moulds and fires urns at a portable crafter, restocking soft clay from a bank preset. " +
        "Stand where the crafter and a bank are both in reach.",
    category = ScriptCategory.CRAFTING,
)
class UrnCrafter : Script(), ConfigurableScript {

    private val urn = EnumConfigItem(
        name = "Urn",
        description = "Which urn to make. Both halves come from the same choice.",
        enumValues = Urn.entries.toTypedArray(),
        initialValue = Urn.FISHING,
    )

    private val clayPreset = IntConfigItem(
        name = "Soft clay preset",
        description = "The bank preset holding a backpack of soft clay. 0 loads whichever was used last.",
        initialValue = 0,
        min = 0,
        max = 9,
    )

    private val tracker = SkillTracker(Skill.CRAFTING)

    private var status = "Starting"
    private var stage = Urn.Stage.MOULD
    private var lastXp = 0
    private var lastXpGainMillis = 0L
    private var failedBankings = 0

    override fun onStart() {
        tracker.reset()
        lastXp = getXp(Skill.CRAFTING)
        lastXpGainMillis = System.currentTimeMillis()
        println("[UrnCrafter] Making ${urn.value} urns")
    }

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (!isPlayerBusy() && captureSerenSpirit()) return tracker.add("Seren spirits")

        noteProgress()
        if (idleTooLong()) return

        if (isPlayerBusy() || MakeX.inProgress) {
            status = stage.describe
            failedBankings = 0
            return delay(700, 300)
        }

        // The two windows the crafter puts up, in the order it puts them up.
        if (MakeX.isOpen) return chooseInMakeWindow()
        if (isDialogOpen()) return chooseStage()

        val chosen = urn.value
        when {
            inventory.count(chosen.unfiredId) >= Urn.LOAD -> useCrafter(Urn.Stage.FIRE)
            inventory.count(Urn.SOFT_CLAY) >= Urn.LOAD -> useCrafter(Urn.Stage.MOULD)
            bankOpen -> restock()
            else -> openBank()
        }
    }

    /**
     * Picks the urn out of the make window.
     *
     * Both urns of a skill sit under one category, so the category narrows the list to two and the stage
     * decides between them. Nothing here needs either urn's name spelled the way the game spells it.
     */
    private suspend fun chooseInMakeWindow() {
        val chosen = urn.value
        status = stage.describe
        val started = makeX(
            { entry -> chosen.matchesEntry(stage, entry) },
            { category -> category.equals(chosen.category, ignoreCase = true) },
        )
        if (!started) {
            println("[UrnCrafter] The make window offered nothing matching ${chosen.category} for $stage")
            status = "Nothing to make"
            return delay(1200, 400)
        }
        delayUntil(PROCESS_TIMEOUT) { MakeX.inProgress || isPlayerBusy() }
    }

    /** Answers the crafter asking which of the two jobs is wanted. */
    private suspend fun chooseStage() {
        status = "Choosing ${stage.dialogue.lowercase()}"
        if (!continueDialogueContaining(stage.dialogue)) {
            println("[UrnCrafter] The crafter offered no \"${stage.dialogue}\" option")
            return delay(1000, 400)
        }
        delayUntil(INTERFACE_TIMEOUT) { MakeX.isOpen }
    }

    private suspend fun useCrafter(next: Urn.Stage) {
        stage = next
        val crafter = findClosestObject(SEARCH_RANGE) { obj ->
            CRAFTER_IDS.any { it == obj.id || it == obj.visibleTypeId }
        }
        if (crafter == null) {
            status = "No crafter in reach"
            return delay(1500, 500)
        }
        val option = CRAFTER_OPTIONS.firstOrNull { crafter.hasOption(it) }
        if (option == null) {
            println(
                "[UrnCrafter] ${crafter.name()} (${crafter.id}/${crafter.visibleTypeId}) offers none of " +
                    "$CRAFTER_OPTIONS; the one it does offer needs adding",
            )
            status = "Crafter offers no option I know"
            return delay(1500, 500)
        }

        status = "Using the crafter"
        if (!crafter.interact(option)) return delay(800, 300)
        // Either window is a fine outcome: some configurations go straight to the make list.
        delayUntil(INTERFACE_TIMEOUT) { isDialogOpen() || MakeX.isOpen }
    }

    /**
     * Empties the backpack and fills it for the next pass.
     *
     * Unfired urns in the bank come first, because firing them is the half that finishes something; only
     * when there are none left is more clay worth moulding.
     */
    private suspend fun restock() {
        val chosen = urn.value
        if (!inventory.isEmpty) {
            depositAllInventory()
            delay(800, 300)
        }

        // Withdrawing is by name, and the bank entry is the one place the name is already spelled the
        // way the game spells it - so it is read off there rather than written down here and kept in step.
        val stored = bank.firstOrNull { it.id == chosen.unfiredId && it.amount >= Urn.LOAD }
        if (stored != null) {
            status = "Withdrawing unfired urns"
            withdrawBankItem(stored.name)
            delayUntil(INTERFACE_TIMEOUT) { inventory.count(chosen.unfiredId) >= Urn.LOAD }
            if (inventory.count(chosen.unfiredId) >= Urn.LOAD) {
                failedBankings = 0
                tracker.add("Loads")
                return
            }
        }

        if (bank.count(Urn.SOFT_CLAY) >= Urn.LOAD) {
            status = "Loading clay preset"
            loadBankPreset(if (clayPreset.value > 0) clayPreset.value else LAST_PRESET)
            delayUntil(INTERFACE_TIMEOUT) { !bankOpen }
            delay(800, 300)
            if (inventory.count(Urn.SOFT_CLAY) >= Urn.LOAD) {
                failedBankings = 0
                tracker.add("Loads")
                return
            }
        }

        // Neither was there. A bank that has just been opened can report an empty container for a moment,
        // so this is given a few goes before it counts as genuinely out.
        failedBankings++
        status = "No clay or unfired urns"
        if (failedBankings >= MAX_FAILED_BANKINGS) {
            println("[UrnCrafter] No soft clay or unfired urns after $failedBankings tries; stopping")
            return stop()
        }
        closeBank()
        delay(1500, 600)
    }

    private suspend fun openBank() {
        val chest = findClosestObject(SEARCH_RANGE) { obj -> BANK_OPTIONS.any { obj.hasOption(it) } }
        if (chest == null) {
            status = "No bank in reach"
            return delay(1500, 500)
        }
        status = "Opening the bank"
        val option = BANK_OPTIONS.first { chest.hasOption(it) }
        if (!chest.interact(option)) return delay(800, 300)
        delayUntil(INTERFACE_TIMEOUT) { bankOpen }
    }

    private fun noteProgress() {
        val xp = getXp(Skill.CRAFTING)
        if (xp > lastXp) {
            lastXp = xp
            lastXpGainMillis = System.currentTimeMillis()
        }
    }

    /** Nothing made for a while means something is wrong that waiting will not fix. */
    private fun idleTooLong(): Boolean {
        if (System.currentTimeMillis() - lastXpGainMillis <= NO_PROGRESS_TIMEOUT) return false
        println("[UrnCrafter] No Crafting experience in ${NO_PROGRESS_TIMEOUT / 1000}s; stopping")
        stop()
        return true
    }

    override fun render() {
        val chosen = urn.value
        ImGuiDsl.window("Urn Crafter") {
            section("Making")
            text("$chosen urns")
            text("Category: ${chosen.category}")
            separator()
            section("Progress")
            text("Status: $status")
            text("Loads: ${tracker.countOf("Loads")}")
            text("XP/hr: ${tracker.xpPerHour(Skill.CRAFTING)}")
            xpProgressBar(Skill.CRAFTING)
        }
    }

    override fun onStop() =
        println("[UrnCrafter] Stopped after ${tracker.countOf("Loads")} loads of ${urn.value} urns")

    private companion object {
        const val SEARCH_RANGE = 12
        const val INTERFACE_TIMEOUT = 6_000L
        const val PROCESS_TIMEOUT = 5_000L
        const val NO_PROGRESS_TIMEOUT = 90_000L
        const val LAST_PRESET = 0

        /** A portable crafter appears as any of these depending on the variant placed. */
        val CRAFTER_IDS = intArrayOf(106594, 106595, 106596, 106597)

        /**
         * A crafter's left-click option is set by whoever placed it, so the wording varies and the first
         * one the placed object actually carries is the one used.
         */
        val CRAFTER_OPTIONS = listOf("Craft", "Make", "Use")

        val BANK_OPTIONS = listOf("Load Last Preset", "Bank", "Use", "Open")

        /** A freshly opened bank can read empty for a moment, so being out is confirmed rather than assumed. */
        const val MAX_FAILED_BANKINGS = 3
    }
}
