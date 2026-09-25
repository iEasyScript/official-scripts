package com.projectx.script.impl.combat.raksha

import com.projectx.script.api.WarsRetreatTask

internal data class RakshaSettings(
    val waitForFullHp: Boolean = true,
    val useRevolution: Boolean = false,
    val inParty: Boolean = false,
    val isPartyLeader: Boolean = false,
    val partyLeader: String = "",
    val useFamiliar: Boolean = true,
    val healthSolid: Int = 70,
    val healthJellyfish: Int = 70,
    val healthPotion: Int = 60,
    val healthSpecial: Int = 75,
    val prayerNormal: Int = 400,
    val prayerCritical: Int = 10,
    val prayerSpecial: Int = 601,
    val summonConjures: Boolean = false,
    val usePrebuild: Boolean = true,
    val useAdrenCrystal: Boolean = true,
    val bankIfInvFull: Boolean = false,
    val advancedMovement: Boolean = false,
    val surgeDiveChance: Int = 100,
    val minPrayer: Int = 100,
    val minSummoning: Int = 80,
    val minHealth: Int = 80,
    val warsTaskOrder: List<WarsRetreatTask> = DEFAULT_TASK_ORDER,
    val ignoreAnimaPools: Boolean = false,
    val poolKillThreshold: Int = 10,
    val poolDiveDistance: Int = 8,
    val shadowTriggerRange: Int = 1,
    val shadowSafeRange: Int = 2,
    val instakillTriggerRange: Int = 5,
    val instakillSafeRange: Int = 6,
    val bombEscapeDistance: Int = 5,
    val debug: Boolean = false,
) {
    val partySize: Int get() = if (inParty) 2 else 1
    val isJoiner: Boolean get() = inParty && !isPartyLeader

    fun scaled(soloHp: Int): Int = soloHp * partySize

    companion object {
        val DEFAULT_TASK_ORDER = listOf(
            WarsRetreatTask.ALTAR,
            WarsRetreatTask.BANK,
            WarsRetreatTask.CRYSTAL,
            WarsRetreatTask.PREBUILD,
            WarsRetreatTask.PORTAL,
        )

        fun parseTaskOrder(text: String): List<WarsRetreatTask> {
            val parsed = text.split(',', ' ', '>', '-')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { key -> WarsRetreatTask.entries.firstOrNull { it.name == key } }
                .distinct()
                .toMutableList()
            if (parsed.isEmpty()) return DEFAULT_TASK_ORDER
            for (task in DEFAULT_TASK_ORDER) {
                if (task in parsed) continue
                val portal = parsed.indexOf(WarsRetreatTask.PORTAL)
                if (portal >= 0) parsed.add(portal, task) else parsed.add(task)
            }
            return parsed
        }
    }
}
