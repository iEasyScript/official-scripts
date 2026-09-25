package com.projectx.script.impl.combat.raksha

import com.projectx.script.api.CombatStyle
import com.projectx.script.api.RotationStep
import com.projectx.script.api.ServerTick
import com.projectx.script.api.adrenaline
import com.projectx.script.api.allNpcsWithinRange
import com.projectx.script.api.castAbility
import com.projectx.script.api.diveToTile
import com.projectx.script.api.equipFromInventory
import com.projectx.script.api.inventory
import com.projectx.script.api.isDiveReady
import com.projectx.script.api.localPlayer
import org.projectx.core.game.combat.Effect
import java.util.concurrent.ThreadLocalRandom
import java.util.function.BooleanSupplier
import kotlin.math.hypot

internal class RakshaRotations(
    private val settings: RakshaSettings,
    private val fight: RakshaFightActions,
    private val log: (String) -> Unit,
) {
    private var prebuildPasses = 0
    private var prebuildApproachStart: Long? = null

    var restartPrebuild: () -> Unit = {}

    val opener: List<RotationStep> = listOf(
        ability("Vengeance", 1),
        ability("Darkness", 2),
        ability("Vengeance", 1),
        ability("Invoke Lord of Bones", 2),
        ability("Surge", 3),
        ability("Surge", 2),
        ability("Command Vengeful Ghost", 2),
        RotationStep.custom("Walk to safespot") { fight.recordSafespot() }.waitTicks(0),
        ability("Invoke Death", 3),
        ability("Command Skeleton Warrior", 2),
        targetRaksha(1),
        ability("Split Soul", 2),
        vulnerabilityBomb(),
        targetRaksha(1),
        ability("Death Skulls", 4),
        ability("Bloat", 3),
        ability("Soul Sap", 3),
        ability("Divert", 4),
        ability("Touch of Death", 4),
        ability("Soul Sap", 5),
        ability("Command Skeleton Warrior", 2),
        improvise(spend = false),
    )

    val revolutionSetup: List<RotationStep> = opener.subList(0, opener.indexOfFirst { it.label == VULNERABILITY_BOMB } + 1)

    private val phase2: List<RotationStep> = listOf(
        ability("Living Death", 3),
        adrenalinePotion(4),
        ability("Touch of Death", 3),
        ability("Death Skulls", 4),
        volleyOfSouls(4),
        fingerOfDeath(4),
        fingerOfDeath(3),
        ability("Soul Sap", 3),
        ability("Basic Attack", 3),
        ability("Basic Attack", 3),
        ability("Death Skulls", 4),
        ability("Soul Sap", 2),
        ability("Basic Attack", 3),
        ability("Touch of Death", 3),
        ability("Basic Attack", 3),
        volleyOfSouls(3),
        ability("Basic Attack", 3),
        improvise(spend = false),
    )

    private val phase3: List<RotationStep> = listOf(
        fingerOfDeath(3),
        fingerOfDeath(3),
        ability("Basic Attack", 3),
        ability("Death Skulls", 4),
        ability("Bloat", 3),
        volleyOfSouls(4),
        RotationStep.custom("Threads of Fate + target pools") {
            fight.requestPoolClear()
            castAbility("Threads of Fate")
        }.waitTicks(3),
        ability("Basic Attack", 3),
        ability("Soul Sap", 3),
        targetRaksha(1),
        vulnerabilityBomb(),
        volleyOfSouls(3),
        ability("Basic Attack", 3),
        ability("Bloat", 3),
        ability("Soul Sap", 3),
        ability("Bloat", 3),
        ability("Touch of Death", 3),
        improvise(spend = false),
    )

    private val phase4: List<RotationStep> = listOf(
        ability("Anticipation", 1),
        ability("Soul Sap", 3),
        ability("Touch of Death", 3),
        ability("Basic Attack", 3),
        equipIfCarried(ENHANCED_EXCALIBUR, 1),
        equipIfCarried(SOULBOUND_LANTERN, 1),
        conjureArmy(),
        ability("Soul Sap", 3),
        ability("Command Skeleton Warrior", 1),
        ability("Command Vengeful Ghost", 1),
        adrenalinePotion(4),
        vulnerabilityBomb(),
        ability("Soul Sap", 3),
        ability("Basic Attack", 3),
        ability("Basic Attack", 3),
        ability("Soul Sap", 3),
        ability("Death Skulls", 4),
        ability("Ingenuity of the Humans", 1).onlyIf { fight.canSpec() }.orSkip(),
        equipIfCarried(ROAR_OF_AWAKENING, 0),
        equipIfCarried(ODE_TO_DECEIT, 0),
        weaponSpec(2),
        restoreNecroWeapons(1),
        ability("Divert", 3),
        ability("Soul Sap", 3),
        ability("Split Soul", 3),
        ability("Bloat", 3),
        equipIfCarried(OMNI_GUARD, 0),
        weaponSpec(2),
        restoreNecroWeapons(1),
        ability("Basic Attack", 3),
        ability("Soul Sap", 3),
        ability("Command Skeleton Warrior", 1),
        volleyOfSouls(3),
        ability("Touch of Death", 3),
        fingerOfDeath(3),
        equipIfCarried(ESSENCE_OF_FINALITY, 1),
        RotationStep.custom(ESSENCE_OF_FINALITY) { fight.useSpecial(ESSENCE_OF_FINALITY) }
            .onlyIf { fight.canSpec() }.orSkip().waitTicks(3),
        improvise(spend = true),
    )

    val byPhase: Map<Int, List<RotationStep>> = mapOf(2 to phase2, 3 to phase3, 4 to phase4)

    val prebuild: List<RotationStep> = buildList {
        add(RotationStep.custom("Attack Training dummy") { attackDummy() }.waitTicks(2))
        repeat(PREBUILD_BUILDER_ROUNDS) {
            add(
                RotationStep.ability("Soul Sap")
                    .onlyIf { inDummyRange() && Effect.RESIDUAL_SOUL.stacks < PREBUILD_TARGET_SOULS }
                    .waitTicks(6),
            )
            add(
                RotationStep.ability("Touch of Death")
                    .onlyIf { inDummyRange() && Effect.NECROSIS.stacks < PREBUILD_TARGET_NECROSIS }
                    .waitTicks(16),
            )
        }
        add(RotationStep.custom("Prebuild loop until stacked") { prebuildLoopGuard() }.waitTicks(2))
        add(RotationStep.custom("Dive toward next stop") { diveTowardPortal() }.waitTicks(2))
    }

    fun prebuildComplete(): Boolean =
        Effect.RESIDUAL_SOUL.stacks >= PREBUILD_TARGET_SOULS && Effect.NECROSIS.stacks >= PREBUILD_TARGET_NECROSIS

    fun resetPrebuild() {
        prebuildPasses = 0
        prebuildApproachStart = null
    }

    private fun prebuildLoopGuard(): Boolean {
        if (!inDummyRange()) {
            val now = ServerTick.count
            val started = prebuildApproachStart ?: now.also { prebuildApproachStart = it }
            if (now - started > PREBUILD_APPROACH_TIMEOUT_TICKS) {
                log("Could not reach the training dummy - skipping prebuild")
                resetPrebuild()
                return true
            }
            attackDummy()
            restartPrebuild()
            return true
        }
        prebuildApproachStart = null
        val souls = Effect.RESIDUAL_SOUL.stacks
        val necrosis = Effect.NECROSIS.stacks
        if (prebuildComplete()) {
            prebuildPasses = 0
            log("Prebuild complete: $souls souls, $necrosis necrosis - entering")
            return true
        }
        prebuildPasses++
        if (prebuildPasses >= PREBUILD_MAX_PASSES) {
            log("Prebuild gave up after $prebuildPasses passes ($souls souls, $necrosis necrosis)")
            prebuildPasses = 0
            return true
        }
        attackDummy()
        restartPrebuild()
        return true
    }

    private fun diveTowardPortal(): Boolean {
        if (!settings.advancedMovement) return true
        if (ThreadLocalRandom.current().nextInt(100) >= settings.surgeDiveChance) return true
        val px = localPlayer.tileX
        val py = localPlayer.tileY
        val dx = (PREBUILD_DIVE_X - px).toDouble()
        val dy = (PREBUILD_DIVE_Y - py).toDouble()
        val gap = hypot(dx, dy)
        if (gap < 6 || !isDiveReady()) return true
        val scale = if (gap <= DIVE_REACH) 1.0 else DIVE_REACH / gap
        val tx = (px + dx * scale).toInt()
        val ty = (py + dy * scale).toInt()
        val landed = diveToTile(tx, ty, localPlayer.plane)
        log("Prebuild dive -> ($tx, $ty), ${"%.0f".format(gap)} tiles out: ${if (landed) "sent" else "failed"}")
        return true
    }

    private fun attackDummy(): Boolean =
        allNpcsWithinRange(80) { it.id == RakshaIds.TRAINING_DUMMY }.firstOrNull()?.interact("Attack") == true

    private fun inDummyRange(): Boolean =
        allNpcsWithinRange(PREBUILD_ENGAGE_RANGE) { it.id == RakshaIds.TRAINING_DUMMY }.isNotEmpty()

    private fun ability(name: String, wait: Int) = RotationStep.ability(name).waitTicks(wait)

    private fun improvise(spend: Boolean) = RotationStep.improvise(CombatStyle.NECROMANCY, spend).waitTicks(3)

    private fun targetRaksha(wait: Int) = RotationStep.custom("Target Raksha") { fight.attackBoss() }.waitTicks(wait)

    private fun vulnerabilityBomb() = RotationStep.inventory(VULNERABILITY_BOMB).waitTicks(0)

    private fun fingerOfDeath(wait: Int) =
        RotationStep.ability("Finger of Death").onlyIf { Effect.NECROSIS.stacks >= 6 }.otherwise("Touch of Death").waitTicks(wait)

    private fun volleyOfSouls(wait: Int) =
        RotationStep.ability("Volley of Souls").onlyIf { Effect.RESIDUAL_SOUL.stacks >= VOLLEY_MIN_SOULS }
            .otherwise("Soul Sap").waitTicks(wait)

    private fun adrenalinePotion(wait: Int) =
        RotationStep.custom("Adrenaline potion") { fight.drinkAdrenalinePotion() }
            .onlyIf { inventory.hasItem(*RakshaIds.ADRENALINE_POTIONS) && adrenaline < 100 }
            .orSkip()
            .waitTicks(wait)

    private fun conjureArmy() =
        RotationStep.custom("Conjure Undead Army") { fight.summonConjures() }
            .onlyIf { fight.canSummonConjures() }
            .orSkip()
            .waitTicks(4)

    private fun equipIfCarried(name: String, wait: Int) =
        RotationStep.equip(name).onlyIf { inventory.hasItem(name) }.orSkip().waitTicks(wait)

    private fun weaponSpec(wait: Int) =
        RotationStep.custom("Weapon Special Attack") { fight.useSpecial("Weapon Special Attack") }
            .onlyIf { fight.canSpec() }
            .orSkip()
            .waitTicks(wait)

    private fun restoreNecroWeapons(wait: Int) = RotationStep.custom("Re-equip Necromancy weapons") {
        listOf(ROAR_OF_AWAKENING, DEATHGUARD).firstOrNull { inventory.hasItem(it) }?.let { equipFromInventory(it) }
        listOf(ODE_TO_DECEIT, SOULBOUND_LANTERN).firstOrNull { inventory.hasItem(it) }?.let { equipFromInventory(it) }
        true
    }.waitTicks(wait)

    private fun RotationStep.orSkip() = otherwise("Skip $label", BooleanSupplier { true })

    private companion object {
        const val VULNERABILITY_BOMB = "Vulnerability bomb"
        const val ENHANCED_EXCALIBUR = "Enhanced Excalibur"
        const val SOULBOUND_LANTERN = "Soulbound lantern"
        const val DEATHGUARD = "Deathguard"
        const val ROAR_OF_AWAKENING = "Roar of Awakening"
        const val ODE_TO_DECEIT = "Ode to Deceit"
        const val OMNI_GUARD = "Omni guard"
        const val ESSENCE_OF_FINALITY = "Essence of Finality"

        const val VOLLEY_MIN_SOULS = 3
        const val PREBUILD_TARGET_SOULS = 5
        const val PREBUILD_TARGET_NECROSIS = 12
        const val PREBUILD_ENGAGE_RANGE = 10
        const val PREBUILD_BUILDER_ROUNDS = 5
        const val PREBUILD_MAX_PASSES = 10
        const val PREBUILD_APPROACH_TIMEOUT_TICKS = 100
        const val PREBUILD_DIVE_X = 3296
        const val PREBUILD_DIVE_Y = 10143
        const val DIVE_REACH = 9.0
    }
}
