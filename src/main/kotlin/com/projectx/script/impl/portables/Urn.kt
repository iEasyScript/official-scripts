package com.projectx.script.impl.portables

/**
 * An urn, and the two things that have to be made to get one.
 *
 * An urn is made twice. Soft clay is moulded into an unfired urn, and unfired urns are fired into the
 * finished thing - two passes at the same crafter, through the same make window, under the same category.
 * So a run is a cycle rather than a single job, and both halves are described here together.
 *
 * [category] is the heading the make window files this urn under, which is what tells the two urns of one
 * skill apart from every other urn in the list.
 */
enum class Urn(
    private val label: String,
    val category: String,
    val unfiredId: Int,
    val firedId: Int,
) {
    FISHING("Decorated fishing", "Fishing Urns", 20343, 20344),
    COOKING("Decorated cooking", "Cooking Urns", 20373, 20374),
    MINING("Decorated mining", "Mining Urns", 20403, 20404),
    WOODCUTTING("Decorated woodcutting", "Woodcutting Urns", 39008, 39010),
    DIVINATION("Decorated divination", "Divination Urns", 40796, 40798),
    FARMING("Decorated farming", "Farming Urns", 40836, 40838),
    HUNTER("Decorated hunter", "Hunter Urns", 40876, 40878),
    RUNECRAFTING("Decorated runecrafting", "Runecrafting Urns", 40916, 40918),
    SMELTING("Decorated smelting", "Smelting Urns", 44766, 44768),
    INFERNAL("Infernal", "Prayer Urns", 20421, 20422);

    override fun toString() = label

    /**
     * Which of the two the crafter is being asked for on this pass.
     *
     * [dialogue] is matched against what the crafter's menu actually says, read off it rather than guessed:
     * moulding is offered as "Form Clay", which no amount of looking for "Mould" would have found.
     */
    enum class Stage(val dialogue: String, val describe: String) {
        MOULD("Form Clay", "Moulding clay"),
        FIRE("Fire", "Firing urns"),
    }

    fun itemIdFor(stage: Stage): Int = if (stage == Stage.MOULD) unfiredId else firedId

    /**
     * Whether a make-window entry is the one wanted for [stage].
     *
     * Matched on the unfired marker rather than on a full name, because both urns of a skill sit under one
     * category and differ only by it - so the category narrows it to two and this picks between them,
     * without needing either name written down exactly as the game spells it.
     */
    fun matchesEntry(stage: Stage, entryName: String): Boolean {
        val unfired = entryName.contains("(unf", ignoreCase = true) ||
            entryName.contains("unfired", ignoreCase = true)
        return if (stage == Stage.MOULD) unfired else !unfired
    }

    companion object {
        const val SOFT_CLAY = 1761

        /** A full backpack of one stage's input, which is what a pass needs before it is worth starting. */
        const val LOAD = 28
    }
}
