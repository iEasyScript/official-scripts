package com.projectx.script.impl.archaeology

import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.SkillTracker
import com.projectx.script.api.inventory
import com.projectx.script.api.isLoggedIn
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.progressBar
import com.projectx.ui.backend.dsl.scopes.readout
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.valueRow
import com.projectx.ui.backend.flags.WindowFlags
import org.projectx.core.game.skill.Skill

/**
 * Works an account up the Archaeology qualifications - Intern, Assistant, Associate, Professor, Guildmaster -
 * by doing whichever of their requirements is still outstanding.
 *
 * It keeps no notes of its own. Everything it needs is already written into the account: the qualification
 * held, artefacts excavated and restored, collections finished, mysteries solved and research banked are all
 * player vars, and so are the hotspots this account has found. Stopping it mid-dig and starting it again a
 * week later reads the same numbers back and carries on from them; there is no progress file to lose and no
 * way for it to start from the beginning by mistake.
 *
 * Each pass it picks the job that moves the next qualification on: get a research contract out, restore what
 * has been dug, take finished artefacts to their collector, or go and dig the best hotspot the account can
 * reach - and when the last requirement lands, attend the ceremony that actually awards the rank.
 *
 * Mysteries are the one requirement it cannot do for you. They are twenty separate hand-built puzzle chains,
 * so the script counts them, tells you how many are left, and works on everything else meanwhile.
 */
@ScriptDescription(
    name = "Archaeology Qualifications",
    version = "1.1.0",
    author = "Cryptic",
    description = "Works up the Archaeology qualifications: digs, restores, completes collections, keeps " +
        "research running and attends the ceremonies. Resumes from the account's own progress, so " +
        "stopping and starting never repeats work. Start it anywhere.",
    category = ScriptCategory.ARCHAEOLOGY,
)
class ArchaeologyQualifications : Script(), ConfigurableScript {

    private val doCollections = BooleanConfigItem(
        name = "Complete collections",
        description = "Take restored artefacts to their collectors. Needed for Associate and above.",
        initialValue = true,
    )
    private val doResearch = BooleanConfigItem(
        name = "Keep research running",
        description = "Send the research team out whenever it is idle. Needs researchers already hired.",
        initialValue = true,
    )
    private val screenSoil = BooleanConfigItem(
        name = "Screen soil on guild trips",
        description = "Empty the soil box into materials while at the guild, to keep restoration supplied.",
        initialValue = true,
    )

    private val tracker = SkillTracker(Skill.ARCHAEOLOGY)
    private var plan = Plan(Job.IDLE, "Starting up")
    private var lastQualification = -1
    private var checkedSetup = false

    override fun onStart() {
        lastQualification = -1
        checkedSetup = false
        ArchPlanner.reachableAgain()
        println("[Archaeology] Started at ${describeRank()}")
    }

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (!checkedSetup && !checkSetup()) return delay(2400, 800)

        noteQualificationChange()
        plan = ArchPlanner.plan(
            researchIdle = !researchRunning(),
            allowResearch = doResearch.value,
            allowCollections = doCollections.value,
        )

        if (plan.job != Job.IDLE) lastIdleReason = null
        when (plan.job) {
            Job.CEREMONY -> runCeremony()
            Job.RESEARCH -> runResearch()
            Job.COLLECT -> runCollect()
            Job.RESTORE -> runGuildTrip()
            Job.EXCAVATE -> runExcavate()
            Job.IDLE -> runIdle()
        }
    }

    /**
     * Checks the one thing the script cannot get for itself: a mattock and the journal. The journal is what
     * fast travel runs on, so without it the script would be stuck walking, and it is better to say so than
     * to spend an hour looking like it is working.
     */
    private suspend fun checkSetup(): Boolean {
        if (!ArchTravel.hasJournal()) {
            return stopWith(
                "No Archaeology journal. Take one from the desk in the guild office and put it in your " +
                    "backpack - fast travel between dig sites runs on it.",
            )
        }
        if (!inventory.hasItem(ArchIds.JOURNAL)) {
            println(
                "[Archaeology] The journal is in your pocket slot. Fast travel works best with it in the " +
                    "backpack, because the worn option needs the equipment tab open.",
            )
        }
        if (!ArchProgress.tutorialDone) {
            return stopWith(
                "The Archaeology tutorial is not finished. Do that first with Acting Guildmaster Reiniger - " +
                    "it is what awards Intern, and nothing here can stand in for it.",
            )
        }
        checkedSetup = true
        return true
    }

    private suspend fun runExcavate() {
        val hotspot = plan.hotspot ?: return delay(1200, 400)
        when (digAt(hotspot)) {
            DigResult.WORKING -> Unit
            DigResult.BACKPACK_FULL -> runGuildTrip()
            DigResult.UNREACHABLE -> {
                ArchPlanner.markUnreachable(hotspot)
                delay(800, 300)
            }
        }
    }

    /**
     * The trip home: hand in anything a collector wants, restore what is left, and turn the soil box into
     * materials for next time. Doing all three in one visit is what keeps the walking down.
     */
    private suspend fun runGuildTrip() {
        if (doCollections.value && ArchPlanner.heldCollectionArtefacts().isNotEmpty()) {
            handInCollections()
            return
        }
        val restoredBefore = ArchProgress.restored
        restoreArtefacts()
        val gained = ArchProgress.restored - restoredBefore
        if (gained > 0) tracker.add("Artefacts restored", gained)

        if (screenSoil.value) screenAndStore() else storeMaterials()
        if (doResearch.value && !researchRunning()) dispatchResearch()
        delay(600, 250)
    }

    private suspend fun runCollect() {
        if (!handInCollections()) delay(1500, 500)
    }

    private suspend fun runResearch() {
        if (!dispatchResearch()) delay(2000, 700)
    }

    private suspend fun runCeremony() {
        if (attendCeremony()) {
            noteQualificationChange()
            ArchPlanner.reachableAgain()
        } else {
            delay(2500, 900)
        }
    }

    /**
     * Nothing to do: usually a dig site the script has not been shown yet. It says so once rather than every
     * pass, waits a while, and keeps checking - because a trip to the guild or a level can make work appear.
     */
    private suspend fun runIdle() {
        if (ArchProgress.target == null) {
            stopWith("Guildmaster earned - there is nothing left to qualify for")
            return
        }
        if (plan.reason != lastIdleReason) {
            lastIdleReason = plan.reason
            println("[Archaeology] ${plan.reason}. Walk to a dig site once and it will remember the way.")
        }
        delay(15_000, 4_000)
    }

    private var lastIdleReason: String? = null

    private fun noteQualificationChange() {
        val rank = ArchProgress.qualificationRank
        if (rank == lastQualification) return
        if (lastQualification >= 0 && rank > lastQualification)
            println("[Archaeology] Qualified as ${ArchProgress.qualification?.title}")
        lastQualification = rank
    }

    private fun describeRank(): String {
        val held = ArchProgress.qualification?.title ?: "no qualification"
        val next = ArchProgress.target?.title ?: "nothing left"
        return "$held, working towards $next"
    }

    private fun stopWith(reason: String): Boolean {
        println("[Archaeology] $reason")
        stop()
        return false
    }

    /**
     * The overlay answers the question the script exists for: what is still in the way of the next
     * qualification, and what is it doing about it right now.
     */
    override fun render() {
        ImGuiDsl.window("Archaeology Qualifications", WindowFlags.AlwaysAutoResize) {
            val goal = ArchProgress.target
            if (goal == null) {
                text("Guildmaster earned")
                return@window
            }
            readout("arch-qualification") {
                valueRow("Qualification", ArchProgress.qualification?.title ?: "None")
                valueRow("Working towards", goal.title)
                valueRow("Doing", plan.reason)
            }
            for (requirement in ArchProgress.requirements()) {
                val fraction = (requirement.have.toFloat() / requirement.need).coerceIn(0f, 1f)
                progressBar(
                    fraction,
                    overlay = "${requirement.name} ${requirement.have}/${requirement.need}",
                )
            }
            val mysteries = ArchProgress.requirements().firstOrNull { it.name == "Mysteries" }
            if (mysteries != null && !mysteries.met)
                text("${mysteries.remaining} mysteries left - those are solved by hand")
            tracker.draw(this)
        }
    }

    override fun onStop() {
        println(
            "[Archaeology] Stopped at ${describeRank()} after ${tracker.xpGained(Skill.ARCHAEOLOGY)} xp",
        )
    }

}
