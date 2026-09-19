package com.projectx.script.impl.archaeology

/**
 * Every way through a dig site: the fort doors, lifts, ropes, portals, barriers and agility obstacles that
 * stand between where a fast travel drops you and where the hotspots actually are.
 *
 * Generated from the cache by taking every arch_ scenery object that carries a traversal option. That is
 * why it covers the scaffolding at Senntisten and the planks at Everlight as well as the obvious dungeon
 * entrances - the game files name them all the same way, so none of them had to be found by hand.
 *
 * [outward] marks the ones whose name says they lead back out. It is a preference, not a rule: the explorer
 * tries inward-looking obstacles first and will still use an "exit" if that is the only untried way on,
 * because a few of them are two-way.
 */
data class Traversal(
    val objectId: Int,
    val name: String,
    val options: List<String>,
    val site: String,
    val outward: Boolean,
    val agility: Boolean,
)

object ArchTraversal {

    val all: List<Traversal> = listOf(
        Traversal(130300, "Staircase", listOf("Climb-up"), "daemonheim", false, false),
        Traversal(130301, "Staircase", listOf("Climb-down"), "daemonheim", false, false),
        Traversal(116492, "Open door", listOf("Enter"), "everlight", false, false),
        Traversal(116494, "Open door", listOf("Enter"), "everlight", false, false),
        Traversal(116499, "Door", listOf("Leave"), "everlight", true, false),
        Traversal(116503, "Exit", listOf("Descend"), "everlight", true, false),
        Traversal(116586, "Statue of Mesomedes", listOf("Open"), "everlight", false, false),
        Traversal(116589, "Statue of Mesomedes", listOf("Open"), "everlight", false, false),
        Traversal(116591, "Statue of Hebe", listOf("Open"), "everlight", false, false),
        Traversal(116594, "Statue of Hebe", listOf("Open"), "everlight", false, false),
        Traversal(116596, "Statue of Padosan", listOf("Open"), "everlight", false, false),
        Traversal(116599, "Statue of Padosan", listOf("Open"), "everlight", false, false),
        Traversal(116601, "Statue of Tromple", listOf("Open"), "everlight", false, false),
        Traversal(116604, "Statue of Tromple", listOf("Open"), "everlight", false, false),
        Traversal(116629, "Cliff", listOf("Traverse"), "everlight", false, true),
        Traversal(116631, "Bridge", listOf("Traverse"), "everlight", false, true),
        Traversal(116634, "Plank", listOf("Traverse"), "everlight", false, true),
        Traversal(116635, "Cliff", listOf("Traverse"), "everlight", false, true),
        Traversal(116636, "Cave in", listOf("Traverse"), "everlight", false, true),
        Traversal(116691, "Lift", listOf("Descend"), "infernalsource", false, false),
        Traversal(116692, "Tunnel", listOf("Leave"), "infernalsource", true, false),
        Traversal(116693, "Secret passage", listOf("Climb down"), "infernalsource", false, false),
        Traversal(116698, "Secret passage", listOf("Climb down"), "infernalsource", false, false),
        Traversal(116699, "Secret passage", listOf("Climb down"), "infernalsource", false, false),
        Traversal(116713, "Passage", listOf("Leave"), "infernalsource", true, false),
        Traversal(116732, "Portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116734, "Portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116735, "Portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116736, "Chaos portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116737, "Dagon portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116738, "Return portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116740, "Aries portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116741, "Taurus portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116742, "Gemini portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116743, "Cancer portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116744, "Leo portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116745, "Virgo portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116746, "Libra portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116747, "Scorpio portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116748, "Sagittarius portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116749, "Capricorn portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116750, "Aquarius portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116751, "Pisces portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116792, "Dagon portal", listOf("Enter"), "infernalsource", false, false),
        Traversal(116920, "Fort entrance", listOf("Open"), "kharidet", false, false),
        Traversal(116925, "Fort entrance", listOf("Open"), "kharidet", false, false),
        Traversal(116926, "Fort entrance", listOf("Enter"), "kharidet", false, false),
        Traversal(116927, "Rope", listOf("Leave"), "kharidet", true, false),
        Traversal(116928, "Maximum security barrier", listOf("Pass"), "kharidet", false, false),
        Traversal(116929, "Legatus barrier", listOf("Pass"), "kharidet", false, false),
        Traversal(116930, "Pontifex barrier", listOf("Pass"), "kharidet", false, false),
        Traversal(116931, "Door", listOf("Leave"), "kharidet", true, false),
        Traversal(116932, "Door", listOf("Open"), "kharidet", false, false),
        Traversal(116933, "Door", listOf("Open"), "kharidet", false, false),
        Traversal(116934, "Door", listOf("Enter"), "kharidet", false, false),
        Traversal(116935, "Door", listOf("Leave"), "kharidet", true, false),
        Traversal(116936, "Praetorium war table", listOf("Enter"), "kharidet", false, false),
        Traversal(116938, "Praetorium war table", listOf("Enter"), "kharidet", false, false),
        Traversal(116939, "Praetorium war table", listOf("Leave"), "kharidet", true, false),
        Traversal(116960, "Vault exit", listOf("Leave"), "kharidet", true, false),
        Traversal(116961, "Vault exit", listOf("Leave"), "kharidet", true, false),
        Traversal(116962, "Vault exit", listOf("Leave"), "kharidet", true, false),
        Traversal(116963, "Vault exit", listOf("Leave"), "kharidet", true, false),
        Traversal(116971, "Prison door", listOf("Enter"), "kharidet", false, false),
        Traversal(116974, "Prison door", listOf("Enter"), "kharidet", false, false),
        Traversal(117079, "Crumbled wall", listOf("Traverse"), "kharidet", false, true),
        Traversal(117080, "Crumbled wall", listOf("Traverse"), "kharidet", false, true),
        Traversal(117081, "Crumbled wall", listOf("Traverse"), "kharidet", false, true),
        Traversal(137388, "Temple doors", listOf("Enter"), "moonrise", false, false),
        Traversal(137389, "Temple doors", listOf("Exit"), "moonrise", true, false),
        Traversal(117418, "Ancient casket", listOf("Open"), "office", false, false),
        Traversal(119042, "Door", listOf("Enter"), "orthen", false, false),
        Traversal(119043, "Stairs out", listOf("Exit"), "orthen", true, false),
        Traversal(119049, "Door", listOf("Enter"), "orthen", false, false),
        Traversal(119050, "Stairs out", listOf("Exit"), "orthen", true, false),
        Traversal(119060, "Stairs down", listOf("Enter"), "orthen", false, false),
        Traversal(119064, "Stairs down", listOf("Enter"), "orthen", false, false),
        Traversal(119065, "Stairs out", listOf("Exit"), "orthen", true, false),
        Traversal(119066, "Door", listOf("Enter"), "orthen", false, false),
        Traversal(119067, "Stairs out", listOf("Exit"), "orthen", true, false),
        Traversal(116786, "Portal chamber", listOf("Leave"), "research", false, false),
        Traversal(116787, "Portal chamber", listOf("Leave"), "research", false, false),
        Traversal(116788, "Portal chamber", listOf("Leave"), "research", false, false),
        Traversal(130288, "Imposing door", listOf("Enter"), "research", false, false),
        Traversal(130291, "Imposing door", listOf("Enter"), "research", false, false),
        Traversal(130292, "Imposing door", listOf("Enter"), "research", false, false),
        Traversal(121120, "Scaffolding", listOf("Squeeze through"), "senntisten", false, false),
        Traversal(121122, "Scaffolding", listOf("Squeeze through"), "senntisten", false, false),
        Traversal(121123, "Door", listOf("Exit"), "senntisten", true, false),
        Traversal(121125, "Door", listOf("Exit"), "senntisten", true, false),
        Traversal(121126, "Shadow of the Monolith", listOf("Leave"), "senntisten", false, false),
        Traversal(121127, "Scaffolding", listOf("Squeeze through"), "senntisten", false, false),
        Traversal(121130, "Scaffolding", listOf("Squeeze through"), "senntisten", false, false),
        Traversal(117161, "Rope", listOf("Climb down"), "stormguard", false, false),
        Traversal(117162, "Rope", listOf("Climb up"), "stormguard", false, false),
        Traversal(117163, "Rope", listOf("Climb down"), "stormguard", false, false),
        Traversal(117164, "Rope", listOf("Climb up"), "stormguard", false, false),
        Traversal(117179, "Gap", listOf("Traverse"), "stormguard", false, true),
        Traversal(117180, "Gap", listOf("Traverse"), "stormguard", false, true),
        Traversal(117181, "Gap", listOf("Traverse"), "stormguard", false, true),
        Traversal(117182, "Gap", listOf("Traverse"), "stormguard", false, true),
        Traversal(117183, "Gap", listOf("Traverse"), "stormguard", false, true),
        Traversal(117243, "Lift", listOf("Descend"), "warforge", false, false),
        Traversal(117244, "Lift", listOf("Climb up"), "warforge", true, false),
        Traversal(117245, "Tunnels entrance", listOf("Enter"), "warforge", false, false),
        Traversal(117248, "Tunnels entrance", listOf("Enter"), "warforge", false, false),
        Traversal(117249, "Tunnels exit", listOf("Exit"), "warforge", true, false),
        Traversal(117250, "Forge doors", listOf("Open"), "warforge", false, false),
        Traversal(117251, "Forge doors", listOf("Open"), "warforge", false, false),
        Traversal(117252, "Forge doors", listOf("Open"), "warforge", false, false),
        Traversal(117309, "Tunnel entrance", listOf("Enter"), "warforge", false, false),
        Traversal(117310, "Tunnel entrance", listOf("Enter"), "warforge", false, false),
        Traversal(117311, "Tunnel entrance", listOf("Enter"), "warforge", false, false),
        Traversal(117312, "Tunnel entrance", listOf("Enter"), "warforge", false, false),
        Traversal(117330, "Gap", listOf("Traverse"), "warforge", false, true),
    )

    private val byObject: Map<Int, Traversal> = all.associateBy { it.objectId }

    /**
     * Every option that means "this leads somewhere", best first.
     *
     * Matching on the option rather than on an id is what makes this work at all. A placed object is usually
     * a morph parent whose id is nowhere in the cache's named list - the Kharid-et fort entrance stands in
     * the world as 116920 while the cache calls the open one 116926 - and the engine resolves the morph
     * before answering hasOption. So the option is the reliable question, and the id table below is only
     * used to tell an agility obstacle from a way back out.
     */
    val OPTIONS: List<String> = listOf(
        "Traverse", "Squeeze through", "Squeeze", "Cross", "Jump",
        "Enter", "Go through", "Descend", "Climb down", "Climb-down",
        "Pass", "Open", "Ascend", "Climb up", "Climb-up",
        "Exit", "Leave",
    )

    fun of(objectId: Int): Traversal? = byObject[objectId]

    /** Whether this scenery is one the script has a name and a leaning for. */
    fun isKnown(objectId: Int): Boolean = byObject.containsKey(objectId)
}
