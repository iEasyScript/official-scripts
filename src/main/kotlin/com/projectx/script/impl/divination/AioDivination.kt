package com.projectx.script.impl.divination

import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.SkillTracker
import com.projectx.script.api.captureSerenSpirit
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.findSerenSpirit
import com.projectx.script.api.inventory
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.localPlayer
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import com.projectx.util.gaussian
import org.projectx.core.game.skill.Skill

@ScriptDescription(
    name = "AIO Divination",
    version = "1.1.0",
    author = "Cryptic",
    description = "Harvests wisps at any colony, Pale to Incandescent and Elder, converting memories at the rift and " +
        "catching chronicle fragments and Seren spirits. Start it at the colony.",
    category = ScriptCategory.DIVINATION,
)
class AioDivination : Script(), ConfigurableScript {

    private val captureChronicles = BooleanConfigItem(
        name = "Capture chronicle fragments",
        description = "Stop harvesting to catch chronicle fragments that fly past.",
        initialValue = true,
    )
    private val empowerChronicles = BooleanConfigItem(
        name = "Empower chronicle fragments",
        description = "Offer chronicle fragments to the rift for experience once you hold enough.",
        initialValue = true,
    )
    private val empowerAt = IntConfigItem(
        name = "Empower at",
        description = "How many chronicle fragments to hold before empowering them.",
        initialValue = 30,
        min = 1,
        max = 1000,
    )
    private val onlyChronicles = BooleanConfigItem(
        name = "Only chronicle fragments",
        description = "Wait at the colony for chronicle fragments without harvesting wisps.",
        initialValue = false,
    )

    private val skippedFragments = mutableMapOf<Int, Long>()
    private var fragmentRefused = false
    private val tracker = SkillTracker(Skill.DIVINATION)
    private var checkedColony = false

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (!checkedColony) {
            if (findRift("Convert memories") == null && closestWisp() == null)
                return stopWith("No wisp colony here; start next to the wisps")
            checkedColony = true
        }

        if (captureSerenSpirit()) return tracker.add("Seren spirits")
        if (captureChronicles.value && captureFragment()) return
        when {
            inventory.isFull && inventory.hasItem(*MEMORIES) -> convertMemories()
            inventory.isFull -> stopWith("Backpack is full of things that are not memories")
            empowerChronicles.value && inventory.count(*CHRONICLE_FRAGMENTS) >= empowerAt.value -> empowerFragments()
            onlyChronicles.value -> delay(900, 300)
            else -> harvest()
        }
    }

    private suspend fun harvest() {
        val current = findClosestNPC(WISP_RANGE) { it.serverIndex == localPlayer.interactionSid && isHarvestable(it) }
        if (current != null && localPlayer.isAnimating) return watchHarvest(current)

        val wisp = closestWisp() ?: return delay(1400, 500)
        if (!wisp.interact("Harvest")) return delay(700, 250)

        delayUntil(gaussian(8000L, 1500L)) { localPlayer.isAnimating || !wisp.exists() || inventory.isFull }
        watchHarvest(wisp)
        delay(380, 150)
    }

    private fun closestWisp() =
        findClosestNPC(WISP_RANGE) { isHarvestable(it) && isEnriched(it) } ?: findClosestNPC(WISP_RANGE) { isHarvestable(it) }

    /**
     * Harvesting carries on by itself until the spring runs dry, so this only watches for a reason to leave it early:
     * a full backpack, a Seren spirit or chronicle fragment to catch, or an enriched wisp worth switching to.
     */
    private suspend fun watchHarvest(target: NPC) {
        val targetEnriched = isEnriched(target)
        var lastBusy = System.currentTimeMillis()
        delayUntil(HARVEST_TIMEOUT_MILLIS, pollingDelayMillis = 300) {
            val now = System.currentTimeMillis()
            if (localPlayer.isAnimating || localPlayer.isMoving) lastBusy = now
            now - lastBusy > gaussian(IDLE_GRACE_MILLIS, 400L) ||
                inventory.isFull ||
                findSerenSpirit() != null ||
                (captureChronicles.value && nextFragment() != null) ||
                (!targetEnriched && findClosestNPC(WISP_RANGE) { isHarvestable(it) && isEnriched(it) } != null)
        }
    }

    private suspend fun captureFragment(): Boolean {
        val fragment = nextFragment() ?: return false
        val before = inventory.count(*CHRONICLE_FRAGMENTS)
        fragmentRefused = false
        if (!fragment.interact("Capture")) return false

        delayUntil(gaussian(7000L, 1200L)) {
            inventory.count(*CHRONICLE_FRAGMENTS) > before || fragmentRefused || !fragment.exists()
        }
        if (inventory.count(*CHRONICLE_FRAGMENTS) > before) tracker.add("Chronicle fragments")
        else skippedFragments[fragment.serverIndex] = System.currentTimeMillis()
        delay(320, 120)
        return true
    }

    private fun nextFragment(): NPC? {
        val now = System.currentTimeMillis()
        skippedFragments.values.removeIf { now - it > FRAGMENT_SKIP_MILLIS }
        return findClosestNPC(FRAGMENT_RANGE) {
            it.serverIndex !in skippedFragments && it.name() == "Chronicle fragment" && it.hasOption("Capture")
        }
    }

    private suspend fun convertMemories() {
        val rift = findRift("Convert memories") ?: return stopWith("No energy rift nearby to convert memories at")
        if (!rift.interact("Convert memories")) return delay(800, 300)

        delayUntil(gaussian(10000L, 1800L)) { localPlayer.isAnimating || !inventory.hasItem(*MEMORIES) }
        var lastBusy = System.currentTimeMillis()
        delayUntil(CONVERT_TIMEOUT_MILLIS, pollingDelayMillis = 300) {
            val now = System.currentTimeMillis()
            if (localPlayer.isAnimating || localPlayer.isMoving) lastBusy = now
            !inventory.hasItem(*MEMORIES) || now - lastBusy > gaussian(IDLE_GRACE_MILLIS, 400L)
        }
        if (!inventory.hasItem(*MEMORIES)) tracker.add("Conversions")
        delay(450, 180)
    }

    private suspend fun empowerFragments() {
        val rift = findRift("Empower")
        if (rift == null) {
            println("[AioDivination] This rift cannot empower chronicle fragments; turning it off")
            empowerChronicles.value = false
            return
        }
        val before = inventory.count(*CHRONICLE_FRAGMENTS)
        if (rift.interact("Empower"))
            delayUntil(gaussian(12000L, 2000L)) { inventory.count(*CHRONICLE_FRAGMENTS) < before }
        delay(500, 200)
    }

    private fun findRift(option: String) = findClosestObjectToTile(localPlayer.tile, RIFT_RANGE) { it.hasOption(option) }

    private fun isHarvestable(npc: NPC) = npc.hasOption("Harvest") && HARVESTABLE.matches(npc.name())

    private fun isEnriched(npc: NPC) = ENRICHED.matches(npc.name())

    private fun stopWith(reason: String) {
        println("[AioDivination] $reason; stopping")
        stop()
    }

    override fun onEvent(event: Event) {
        if (event is Chat && REFUSALS.any { event.message.contains(it, ignoreCase = true) }) fragmentRefused = true
    }

    override fun render() = tracker.window("AIO Divination")

    override fun onStop() = println("[AioDivination] Stopped after gaining ${tracker.xpGained(Skill.DIVINATION)} xp")

    private companion object {
        val CHRONICLE_FRAGMENTS = intArrayOf(29293, 51489)
        const val WISP_RANGE = 25
        const val FRAGMENT_RANGE = 15
        const val RIFT_RANGE = 40
        const val HARVEST_TIMEOUT_MILLIS = 240_000L
        const val CONVERT_TIMEOUT_MILLIS = 120_000L
        const val IDLE_GRACE_MILLIS = 2_600L
        const val FRAGMENT_SKIP_MILLIS = 30_000L

        const val TIERS =
            "Pale|Flickering|Bright|Glowing|Sparkling|Gleaming|Vibrant|Lustrous|Brilliant|Radiant|Luminous|Incandescent|Elder"
        val HARVESTABLE = Regex("(?i)(enriched )?($TIERS) (wisp|spring)")
        val ENRICHED = Regex("(?i)enriched ($TIERS) (wisp|spring)")

        /** Every normal and enriched colony memory; none of them stack. */
        val MEMORIES = (29384..29406).toList().toIntArray() + intArrayOf(31326, 31327)

        val REFUSALS = listOf("already been caught", "can't capture", "cannot capture")
    }
}
