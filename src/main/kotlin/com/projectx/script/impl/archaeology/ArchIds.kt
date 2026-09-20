package com.projectx.script.impl.archaeology

import world.gregs.voidps.type.Tile

/**
 * The fixed ids the Archaeology skill is built on: the varbits the game keeps its progress in, the guild's
 * scenery and staff, and the items a dig needs.
 *
 * These are the values the client itself reads, taken from the cache rather than observed, so they do not
 * drift with a game update the way a list of remembered coordinates would. The guild tiles are the only
 * measured values here, and every one of them is only a starting point for a search: the script walks to
 * the tile and then finds the object by id, so a moved bench costs a longer walk rather than a broken run.
 */
internal object ArchIds {

    // ---- Progress, all in the player var domain ----

    /** 0 unqualified, then 1 Intern .. 5 Guildmaster. The single source of truth for "where am I". */
    const val QUALIFICATION = 46468

    /** The Archaeology tutorial's progress. Intern is awarded for finishing it, and nothing else will do. */
    const val TUTORIAL = 46463

    /** Damaged artefacts excavated, lifetime: The Discoverer. */
    const val DISCOVERY_TOTAL = 46471

    /** Damaged artefacts restored, lifetime: The Restorer. */
    const val RESTORATION_TOTAL = 46472

    /** Research time in game ticks, as a varp rather than a varbit: The Researcher. */
    const val RESEARCH_TIME_VARP = 9278

    /** The two varps whose every bit is one solved mystery, so they can be counted in one read each. */
    val MYSTERY_VARPS = intArrayOf(9302, 9303)

    /** Set while a qualification has been earned but its ceremony has not been attended. */
    val QUALIFICATION_OWED = intArrayOf(46488, 46489, 46490, 46491, 46492)

    /**
     * The site's research-unlocked bit, indexed by the site's map slot (1..9). The game sets it when a site
     * first becomes yours, so it is a good "can I go there" signal, but it is not the gate itself: the level
     * from [ArchData.digSites] is, and a failed fast travel is the only answer that is never stale.
     */
    val SITE_UNLOCKED = mapOf(
        1 to 46760, // Kharid-et
        2 to 46761, // Everlight
        3 to 46762, // Infernal Source
        4 to 46763, // Stormguard Citadel
        5 to 46764, // Warforge
        6 to 48117, // Orthen
        7 to 49862, // Senntisten
        8 to 55592, // Daemonheim
        9 to 61047, // Moonrise
    )

    // ---- The Archaeology Guild campus, north-east of Varrock ----

    val GUILD_WORKBENCH_TILE: Tile = Tile.of(3355, 3394, 0)
    val GUILD_STORAGE_TILE: Tile = Tile.of(3358, 3393, 0)
    val GUILD_DONATION_TILE: Tile = Tile.of(3350, 3400, 0)
    val GUILD_SCREENING_TILE: Tile = Tile.of(3378, 3384, 0)
    val GUILD_BANK_TILE: Tile = Tile.of(3362, 3359, 0)
    val GUILDMASTER_TILE: Tile = Tile.of(3325, 3376, 0)
    val HEAD_OF_RESEARCH_TILE: Tile = Tile.of(3365, 3355, 0)

    /** The dig sites map hangs in the guild hall, a few tiles from where the journal drops you. */
    val DIG_SITES_MAP_TILE: Tile = Tile.of(3327, 3374, 0)
    const val DIG_SITES_MAP_OBJECT = 116436

    /**
     * Where each dig site's fast travel puts you down, by the site's map slot.
     *
     * Measured by taking every one of them, rather than read from the cache, because the cache has no
     * coordinates at all. Moonrise is absent: it was locked on the account these were taken on, so there is
     * no honest value for it, and the script falls back to learning that one from a first visit.
     */
    val SITE_ARRIVAL: Map<Int, Tile> = mapOf(
        1 to Tile.of(3345, 3194, 0), // Kharid-et
        2 to Tile.of(3697, 3206, 0), // Everlight
        3 to Tile.of(3271, 3504, 0), // Infernal Source
        4 to Tile.of(2680, 3403, 0), // Stormguard Citadel
        5 to Tile.of(2409, 2824, 0), // Warforge
        6 to Tile.of(5457, 2339, 0), // Orthen
        7 to Tile.of(1784, 1296, 0), // Senntisten
        8 to Tile.of(3428, 3699, 0), // Daemonheim
    )

    /**
     * Where to head for a sub-site that sits behind a way in, for the web walker to route to.
     *
     * Measured rather than read off a map: Kharid-et's fort entrance was taken to each of the destinations it
     * offers and the landing tile read off. "Main fortress" arrives at 2447,7617 and "Prison block" at
     * 2247,7608 - and the prison block is the Carcerem, which its name does not say.
     *
     * The walker knows that entrance as a link and answers its "Choose destination." itself, so walking to one
     * of these tiles is the whole of getting inside; the local sweep then finds the hotspot. The exterior site
     * is deliberately absent - it is already outside, and sending the walker into the fort to reach it would
     * be worse than not trying.
     *
     * Keyed on the distinctive word in a hotspot's sub-site, which reads "Kharid-et - Carcerem excavation site".
     */
    val SUB_SITE_ENTRY: Map<String, Tile> = mapOf(
        // The prison block, which is the Carcerem - its name does not say so, and Praesidio remains stands in it.
        "carcerem" to Tile.of(2247, 7608, 0),
        // Where the prison block's own door comes out, with Castra debris beside it.
        "barracks" to Tile.of(2442, 7581, 0),
        // Culinarum and Armarium share a sub-site; this is where Culinarum debris was dug.
        "culinarum" to Tile.of(2488, 7589, 0),
        // Not seen directly - the main fortress landing, which the chapel is reached from.
        "chapel" to Tile.of(2447, 7617, 0),
    )

    /** Archaeologist's workbench, in its several forms; all of them carry Restore. */
    val WORKBENCH_OBJECTS = intArrayOf(115421, 125133, 118958)

    /** Material storage containers: the guild's, the per-dig-site ones, and the members' variants. */
    val MATERIAL_STORAGE_OBJECTS = intArrayOf(115422, 116439, 117403, 117404, 125134, 116438)

    /** The per-site materials cart, which deposits straight into material storage without a trip home. */
    val MATERIALS_CART_OBJECTS = intArrayOf(116292, 116293, 116294, 116295, 116296, 116297)

    /** Wet and dry screening meshes, and the guild's screening station. */
    val SCREENING_OBJECTS = intArrayOf(115418, 115419, 115420)

    const val MUSEUM_DONATION_BIN = 115423
    const val COLLECTOR_DELIVERY_BOX = 139544
    const val GUILD_BANK_CHEST = 43807

    const val GUILDMASTER_NPC = 26929
    const val HEAD_OF_RESEARCH_NPC = 26938

    // ---- Items ----

    const val SOIL_BOX = 49538
    const val JOURNAL = 49429

    /** Every mattock, so one can be found to add to the tool belt if the belt slot is empty. */
    val MATTOCKS = intArrayOf(
        49534, 49542, 49545, 49548, 49551, 49554, 49557, 49560, 49562, 49564, 49566, 49569,
        49571, 49572, 49574, 49575, 49581, 49582, 49584, 49586, 48445,
    )

    /** Set to the tier of mattock on the tool belt, or 0 with none. */
    const val TOOLBELT_MATTOCK = 45999

    /**
     * Every excavation material. Materials are what restoration spends, so they are worth banking; they are
     * also the bulk of what a dig produces, which is why the script sends them away rather than walking them.
     */
    val MATERIALS = intArrayOf(
        49444, 49445, 49446, 49448, 49450, 49452, 49454, 49456, 49458, 49460, 49462, 49464, 49466,
        49468, 49470, 49472, 49474, 49476, 49478, 49480, 49482, 49484, 49486, 49488, 49490, 49492,
        49494, 49496, 49498, 49500, 49502, 49504, 49506, 49508, 49510, 49512, 49514,
        50686, 50688, 50690, 50692, 50694,
    )

    // ---- Interfaces ----

    /**
     * The dig sites map's own interface. Each site is a slot on [SITE_MAP_ICONS], counted from zero in the
     * order the sites are listed, and its second option is the fast travel.
     */
    const val SITE_MAP = 667
    const val SITE_MAP_ICONS = 11
    const val SITE_MAP_CLOSE = 27
    const val SITE_MAP_FAST_TRAVEL_OP = 2

    /** The certificate shown at a qualification ceremony; closing it is what finishes the ceremony. */
    const val QUALIFICATION_INTERFACE = 664
    const val QUALIFICATION_CLOSE = 16

    const val RESEARCH_INTERFACE = 693
    const val RESEARCH_CLOSE = 64

    /** Idle while zero; anything else means a contract is out earning research time. */
    const val RESEARCH_CONTRACT_STATE = 46617

    const val MATERIAL_STORAGE_INTERFACE = 660
    const val MATERIAL_STORAGE_CLOSE = 14
    const val MATERIAL_STORAGE_DEPOSIT = 30
}
