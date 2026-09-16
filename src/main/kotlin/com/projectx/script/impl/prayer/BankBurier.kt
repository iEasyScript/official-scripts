package com.projectx.script.impl.prayer

import com.projectx.game.interfaces.Bank
import com.projectx.game.nxt.interfaces.InterfaceComponent
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.SkillTracker
import com.projectx.script.api.bankOpen
import com.projectx.script.api.bankWithdrawNotes
import com.projectx.script.api.captureSerenSpirit
import com.projectx.script.api.closeBank
import com.projectx.script.api.depositBankInventory
import com.projectx.script.api.inventory
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.openClosestBank
import com.projectx.script.api.setBankWithdrawNotes
import com.projectx.util.gaussian
import org.projectx.core.game.combat.Effect
import org.projectx.core.game.skill.Skill

@ScriptDescription(
    name = "Bank Burier",
    version = "1.1.0",
    author = "Cryptic",
    description = "Buries bones or scatters ashes at any bank, keeping Powder of burials active. " +
        "Start next to a bank with the bones or ashes and some Powder of burials in it.",
    category = ScriptCategory.PRAYER,
)
class BankBurier : Script(), ConfigurableScript {

    enum class Remains(private val label: String, val action: String, vararg val itemIds: Int) {
        BONES("Bones", "Bury", 526),
        BURNT_BONES("Burnt bones", "Bury", 528),
        WOLF_BONES("Wolf bones", "Bury", 2859),
        MONKEY_BONES("Monkey bones", "Bury", 3183),
        BAT_BONES("Bat bones", "Bury", 530),
        BIG_BONES("Big bones", "Bury", 532),
        JOGRE_BONES("Jogre bones", "Bury", 3125),
        ZOGRE_BONES("Zogre bones", "Bury", 4812),
        SHAIKAHAN_BONES("Shaikahan bones", "Bury", 3123),
        BABY_DRAGON_BONES("Baby dragon bones", "Bury", 534),
        WYVERN_BONES("Wyvern bones", "Bury", 6812),
        DRAGON_BONES("Dragon bones", "Bury", 536),
        FAYRG_BONES("Fayrg bones", "Bury", 4830),
        RAURG_BONES("Raurg bones", "Bury", 4832),
        DAGANNOTH_BONES("Dagannoth bones", "Bury", 6729),
        AIRUT_BONES("Airut bones", "Bury", 30209),
        OURG_BONES("Ourg bones", "Bury", 4834, 14793),
        HARDENED_DRAGON_BONES("Hardened dragon bones", "Bury", 35008),
        DRAGONKIN_BONES("Dragonkin bones", "Bury", 51858),
        FROST_DRAGON_BONES("Frost dragon bones", "Bury", 18832),
        DINOSAUR_BONES("Dinosaur bones", "Bury", 48075),
        REINFORCED_DRAGON_BONES("Reinforced dragon bones", "Bury", 35010),
        IMPIOUS_ASHES("Impious ashes", "Scatter", 20264),
        ACCURSED_ASHES("Accursed ashes", "Scatter", 20266),
        INFERNAL_ASHES("Infernal ashes", "Scatter", 20268),
        TORTURED_ASHES("Tortured ashes", "Scatter", 33260),
        SEARING_ASHES("Searing ashes", "Scatter", 34159);

        override fun toString() = label
    }

    private val remains = EnumConfigItem(
        name = "Bones or ashes",
        description = "What to take from the bank and bury or scatter.",
        enumValues = Remains.entries.toTypedArray(),
        initialValue = Remains.DRAGON_BONES,
    )

    private val tracker = SkillTracker(Skill.PRAYER)
    private var bankAttempts = 0

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (captureSerenSpirit()) return tracker.add("Seren spirits")

        val target = remains.value
        val powderDown = Effect.POWDER_OF_BURIALS.notActive
        when {
            powderDown && inventory.hasItem(POWDER_OF_BURIALS) -> scatterPowder()
            powderDown || !inventory.hasItem(*target.itemIds) -> restock(target)
            else -> use(target)
        }
    }

    private suspend fun scatterPowder() {
        closeBankIfOpen()
        if (inventory.clickItem(POWDER_OF_BURIALS, "Scatter"))
            delayUntil(gaussian(3000L, 600L)) { Effect.POWDER_OF_BURIALS.active }
        delay(520, 180)
    }

    private suspend fun use(target: Remains) {
        closeBankIfOpen()
        val before = inventory.count(*target.itemIds)
        if (inventory.getItem(*target.itemIds)?.click(target.action) == true) {
            delayUntil(gaussian(1800L, 400L)) { inventory.count(*target.itemIds) < before }
            if (inventory.count(*target.itemIds) < before) tracker.add(target.toString())
        }
        delay(190, 70)
    }

    private suspend fun restock(target: Remains) {
        if (!bankOpen) return openBank()

        val bankItems = runCatching { Bank.fetchBankItemsArray() }.getOrNull()
        if (bankItems.isNullOrEmpty()) return delay(420, 140)

        val needsPowder = Effect.POWDER_OF_BURIALS.notActive && !inventory.hasItem(POWDER_OF_BURIALS)
        val keep = target.itemIds.toSet() + POWDER_OF_BURIALS
        if (inventory.any { it.id !in keep } || (needsPowder && inventory.freeSlots == 0)) {
            if (depositBankInventory()) delayUntil(gaussian(2400L, 500L)) { inventory.usedSlots == 0 }
            return delay(380, 140)
        }
        if (bankWithdrawNotes && setBankWithdrawNotes(false)) {
            delayUntil(gaussian(1500L, 300L)) { !bankWithdrawNotes }
            return delay(300, 110)
        }

        if (needsPowder && !withdraw(bankItems, WITHDRAW_ONE, POWDER_OF_BURIALS)) return outOf("Powder of burials")
        if (!inventory.hasItem(*target.itemIds) && !withdraw(bankItems, WITHDRAW_ALL, *target.itemIds))
            return outOf(target.toString())
        closeBankIfOpen()
    }

    /** Clicks the bank slot holding one of [itemIds]; false only when the bank has none of them. */
    private suspend fun withdraw(bankItems: List<InterfaceComponent>, option: Int, vararg itemIds: Int): Boolean {
        val slot = bankItems.firstOrNull { it.itemId in itemIds } ?: return false
        if (Bank.doBankAction(Bank.BANK_ITEMS_COMPONENT_ID, slot.slotId, option))
            delayUntil(gaussian(2400L, 500L)) { inventory.hasItem(*itemIds) }
        delay(340, 130)
        return true
    }

    private suspend fun openBank() {
        if (openClosestBank()) {
            delayUntil(gaussian(9000L, 1500L)) { bankOpen }
            if (bankOpen) {
                bankAttempts = 0
                return delay(450, 160)
            }
        }
        if (++bankAttempts >= MAX_BANK_ATTEMPTS) {
            println("[BankBurier] Could not open a bank; start next to a bank booth, chest or banker")
            return stop()
        }
        delay(1200, 400)
    }

    private suspend fun closeBankIfOpen() {
        if (!bankOpen) return
        if (closeBank()) delayUntil(gaussian(2000L, 400L)) { !bankOpen }
        delay(260, 90)
    }

    private fun outOf(item: String) {
        println("[BankBurier] Out of $item; stopping")
        stop()
    }

    override fun render() = tracker.window("Bank Burier")

    override fun onStop() = println("[BankBurier] Stopped after gaining ${tracker.xpGained(Skill.PRAYER)} xp")

    private companion object {
        const val POWDER_OF_BURIALS = 52805
        const val WITHDRAW_ONE = 2
        const val WITHDRAW_ALL = 7
        const val MAX_BANK_ATTEMPTS = 3
    }
}
