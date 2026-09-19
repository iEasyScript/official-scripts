package com.projectx.script.impl.archaeology.aio

/** An Archaeology dig site, used to narrow the hotspot dropdown down to one site's worth of choices. */
enum class DigSite(private val label: String) {
    KHARID_ET("Kharid-et"),
    INFERNAL_SOURCE("Infernal Source"),
    EVERLIGHT("Everlight"),
    SENNTISTEN("Senntisten"),
    MOONRISE("Moonrise"),
    ORTHEN("Orthen"),
    STORMGUARD_CITADEL("Stormguard Citadel"),
    WARFORGE("Warforge"),
    DAEMONHEIM("Daemonheim"),
    OTHER("Other locations"),
    ;

    override fun toString() = label
}

/**
 * Every excavation hotspot in the game, by the object name the script clicks, the Archaeology level it needs,
 * and the dig site and excavation site it sits in. Names were taken from the game's own cache, so they match
 * what [com.projectx.game.nxt.entity.location.SceneObject.name] returns.
 *
 * A few names are not unique game-wide ('Barricade' is used by dozens of unrelated objects), so a lookup must
 * always pair the name with the 'Excavate' option.
 */
enum class ExcavationHotspot(
    val objectName: String,
    val level: Int,
    val digSite: DigSite,
    /** The excavation site within [digSite], or empty for a hotspot that stands on its own. */
    val area: String,
) {

    // Kharid-et
    VENATOR_REMAINS("Venator remains", 5, DigSite.KHARID_ET, "Exterior"),
    FORT_DEBRIS("Fort debris", 12, DigSite.KHARID_ET, "Exterior"),
    LEGIONARY_REMAINS("Legionary remains", 12, DigSite.KHARID_ET, "Barracks"),
    CASTRA_DEBRIS("Castra debris", 17, DigSite.KHARID_ET, "Barracks"),
    ADMINISTRATUM_DEBRIS("Administratum debris", 25, DigSite.KHARID_ET, "Barracks"),
    PRAESIDIO_REMAINS("Praesidio remains", 47, DigSite.KHARID_ET, "Carcerem"),
    CARCEREM_DEBRIS("Carcerem debris", 58, DigSite.KHARID_ET, "Carcerem"),
    KHARID_ET_CHAPEL_DEBRIS("Kharid-et chapel debris", 74, DigSite.KHARID_ET, "Chapel"),
    PONTIFEX_REMAINS("Pontifex remains", 81, DigSite.KHARID_ET, "Chapel"),
    ORCUS_ALTAR("Orcus altar", 86, DigSite.KHARID_ET, "Chapel"),
    ARMARIUM_DEBRIS("Armarium debris", 93, DigSite.KHARID_ET, "Culinarum"),
    CULINARUM_DEBRIS("Culinarum debris", 100, DigSite.KHARID_ET, "Culinarum"),
    ANCIENT_MAGICK_MUNITIONS("Ancient magick munitions", 107, DigSite.KHARID_ET, "Praetorium"),
    PRAETORIAN_REMAINS("Praetorian remains", 114, DigSite.KHARID_ET, "Praetorium"),
    WAR_TABLE_DEBRIS("War table debris", 118, DigSite.KHARID_ET, "Praetorium"),

    // Infernal Source
    LODGE_BAR_STORAGE("Lodge bar storage", 20, DigSite.INFERNAL_SOURCE, "Star Lodge cellar"),
    LODGE_ART_STORAGE("Lodge art storage", 24, DigSite.INFERNAL_SOURCE, "Star Lodge cellar"),
    CULTIST_FOOTLOCKER("Cultist footlocker", 29, DigSite.INFERNAL_SOURCE, "Dungeon of Disorder"),
    SACRIFICIAL_ALTAR("Sacrificial altar", 36, DigSite.INFERNAL_SOURCE, "Dungeon of Disorder"),
    DIS_DUNGEON_DEBRIS("Dis dungeon debris", 45, DigSite.INFERNAL_SOURCE, "Dungeon of Disorder"),
    INFERNAL_ART("Infernal art", 65, DigSite.INFERNAL_SOURCE, "Vestibule of Futility south"),
    SHAKROTH_REMAINS("Shakroth remains", 68, DigSite.INFERNAL_SOURCE, "Vestibule of Futility north-east"),
    ANIMAL_TROPHIES("Animal trophies", 81, DigSite.INFERNAL_SOURCE, "Vestibule of Futility south-east"),
    DIS_OVERSPILL("Dis overspill", 89, DigSite.INFERNAL_SOURCE, "The Harrowing south-east"),
    BYZROTH_REMAINS("Byzroth remains", 98, DigSite.INFERNAL_SOURCE, "The Harrowing north-west"),
    HELLFIRE_FORGE("Hellfire forge", 104, DigSite.INFERNAL_SOURCE, "The Harrowing north-east"),
    CHTHONIAN_TROPHIES("Chthonian trophies", 110, DigSite.INFERNAL_SOURCE, "Dagon Overlook north"),
    TSUTSAROTH_REMAINS("Tsutsaroth remains", 116, DigSite.INFERNAL_SOURCE, "Dagon Overlook south-west"),

    // Everlight
    PRODROMOI_REMAINS("Prodromoi remains", 42, DigSite.EVERLIGHT, "Mass grave"),
    MONOCEROS_REMAINS("Monoceros remains", 48, DigSite.EVERLIGHT, "Mass grave"),
    AMPHITHEATRE_DEBRIS("Amphitheatre debris", 51, DigSite.EVERLIGHT, "Amphitheatre"),
    CERAMICS_STUDIO_DEBRIS("Ceramics studio debris", 56, DigSite.EVERLIGHT, "Amphitheatre"),
    STADIO_DEBRIS("Stadio debris", 61, DigSite.EVERLIGHT, "Dominion Games stadium"),
    DOMINION_GAMES_PODIUM("Dominion Games podium", 69, DigSite.EVERLIGHT, "Dominion Games stadium"),
    OIKOS_STUDIO_DEBRIS("Oikos studio debris", 72, DigSite.EVERLIGHT, "Oikoi"),
    OIKOS_FISHING_HUT_REMNANTS("Oikos fishing hut remnants", 84, DigSite.EVERLIGHT, "Oikoi"),
    ACROPOLIS_DEBRIS("Acropolis debris", 92, DigSite.EVERLIGHT, "Acropolis"),
    ICYENE_WEAPON_RACK("Icyene weapon rack", 100, DigSite.EVERLIGHT, "Dominion Games stadium"),
    STOCKPILED_ART("Stockpiled art", 105, DigSite.EVERLIGHT, "Underwater cave"),
    BIBLIOTHEKE_DEBRIS("Bibliotheke debris", 109, DigSite.EVERLIGHT, "Acropolis"),
    OPTIMATOI_REMAINS("Optimatoi remains", 117, DigSite.EVERLIGHT, "Underwater cave"),

    // Senntisten
    MINISTRY_REMAINS("Ministry remains", 60, DigSite.SENNTISTEN, "Cathedral"),
    CATHEDRAL_DEBRIS("Cathedral debris", 62, DigSite.SENNTISTEN, "Cathedral"),
    MARKETPLACE_DEBRIS("Marketplace debris", 63, DigSite.SENNTISTEN, "Marketplace"),
    INQUISITOR_REMAINS("Inquisitor remains", 64, DigSite.SENNTISTEN, "Marketplace"),
    GLADIATOR_REMAINS("Gladiator remains", 66, DigSite.SENNTISTEN, "Colosseum"),
    CITIZEN_REMAINS("Citizen remains", 67, DigSite.SENNTISTEN, "Bridge"),

    // Moonrise
    LUNAR_WHEEL_DEBRIS("Lunar wheel debris", 52, DigSite.MOONRISE, "Temple periphery"),
    ARBOUR_RUBBLE("Arbour rubble", 59, DigSite.MOONRISE, "Temple periphery"),
    WEAVERS_HUT_DEBRIS("Weaver's hut debris", 66, DigSite.MOONRISE, "Temple periphery"),
    SHRINE_DEBRIS("Shrine debris", 75, DigSite.MOONRISE, "Temple periphery"),
    CAULDRON_DEBRIS("Cauldron debris", 82, DigSite.MOONRISE, "Temple depths"),
    BLOOD_ALTAR("Blood altar", 88, DigSite.MOONRISE, "Temple depths"),

    // Orthen
    DRAGONKIN_REMAINS("Dragonkin remains", 70, DigSite.ORTHEN, "Nodon hibernatorium"),
    ORTHEN_RUBBLE("Orthen rubble", 90, DigSite.ORTHEN, "Nodon hibernatorium"),
    VARANUSAUR_REMAINS("Varanusaur remains", 90, DigSite.ORTHEN, "Crypt of Varanus"),
    DRAGONKIN_RELIQUARY("Dragonkin reliquary", 96, DigSite.ORTHEN, "Crypt of Varanus"),
    DRAGONKIN_COFFIN("Dragonkin coffin", 99, DigSite.ORTHEN, "Crypt of Varanus"),
    AUTOPSY_TABLE("Autopsy table", 101, DigSite.ORTHEN, "Observation outpost"),
    EXPERIMENT_WORKBENCH("Experiment workbench", 102, DigSite.ORTHEN, "Observation outpost"),
    AUGHRA_REMAINS("Aughra remains", 106, DigSite.ORTHEN, "Moksha ritual site"),
    MOKSHA_DEVICE("Moksha device", 108, DigSite.ORTHEN, "Moksha ritual site"),
    XOLO_MINE("Xolo mine", 113, DigSite.ORTHEN, "Xolo city"),
    XOLO_REMAINS("Xolo remains", 119, DigSite.ORTHEN, "Xolo city"),
    SAURTHEN_DEBRIS("Saurthen debris", 120, DigSite.ORTHEN, "Xolo city"),

    // Stormguard Citadel
    IKOVIAN_MEMORIAL("Ikovian memorial", 70, DigSite.STORMGUARD_CITADEL, "Temple of Ikov"),
    KESHIK_GER("Keshik ger", 76, DigSite.STORMGUARD_CITADEL, "Keshik memorial"),
    TAILORY_DEBRIS("Tailory debris", 81, DigSite.STORMGUARD_CITADEL, "Relay station"),
    WEAPONS_RESEARCH_DEBRIS("Weapons research debris", 85, DigSite.STORMGUARD_CITADEL, "Research & Development north-west"),
    GRAVITRON_RESEARCH_DEBRIS("Gravitron research debris", 91, DigSite.STORMGUARD_CITADEL, "Research & Development north-west"),
    KESHIK_TOWER_DEBRIS("Keshik tower debris", 95, DigSite.STORMGUARD_CITADEL, "Dayguard tower"),
    DESTROYED_GOLEM("Destroyed golem", 98, DigSite.STORMGUARD_CITADEL, "Research & Development south-west"),
    KESHIK_WEAPON_RACK("Keshik weapon rack", 103, DigSite.STORMGUARD_CITADEL, "Nightguard tower"),
    FLIGHT_RESEARCH_DEBRIS("Flight research debris", 111, DigSite.STORMGUARD_CITADEL, "Research & Development south-east"),
    AETHERIUM_FORGE("Aetherium forge", 112, DigSite.STORMGUARD_CITADEL, "Howl's workshop"),
    HOWLS_WORKSHOP_DEBRIS("Howl's workshop debris", 118, DigSite.STORMGUARD_CITADEL, "Howl's workshop"),

    // Warforge
    GLADIATORIAL_GOBLIN_REMAINS("Gladiatorial goblin remains", 76, DigSite.WARFORGE, "Crucible arena"),
    CRUCIBLE_STANDS_DEBRIS("Crucible stands debris", 81, DigSite.WARFORGE, "Crucible stands"),
    BARRICADE("Barricade", 83, DigSite.WARFORGE, ""),
    GOBLIN_DORM_DEBRIS("Goblin dorm debris", 83, DigSite.WARFORGE, "South goblin tunnels"),
    BIG_HIGH_WAR_GOD_SHRINE("Big High War God shrine", 89, DigSite.WARFORGE, "North goblin tunnels"),
    YUBIUSK_ANIMAL_PEN("Yu'biusk animal pen", 94, DigSite.WARFORGE, "Animal pens"),
    GOBLIN_TRAINEE_REMAINS("Goblin trainee remains", 97, DigSite.WARFORGE, "North goblin tunnels"),
    KYZAJ_CHAMPIONS_BOUDOIR("Kyzaj champion's boudoir", 100, DigSite.WARFORGE, "South goblin tunnels"),
    WARFORGE_SCRAP_PILE("Warforge scrap pile", 104, DigSite.WARFORGE, "North goblin tunnels"),
    WARFORGE_WEAPON_RACK("Warforge weapon rack", 110, DigSite.WARFORGE, "Thalmund's forge"),
    BANDOS_SANCTUM_DEBRIS("Bandos's sanctum debris", 115, DigSite.WARFORGE, "Bandos's sanctum"),
    MAKESHIFT_PIE_OVEN("Makeshift pie oven", 119, DigSite.WARFORGE, "Thalmund's forge"),

    // Daemonheim
    CASTLE_HALL_RUBBLE("Castle hall rubble", 73, DigSite.DAEMONHEIM, "Castle"),
    TUNNELLING_EQUIPMENT_REPOSITORY("Tunnelling equipment repository", 77, DigSite.DAEMONHEIM, "Castle"),
    BOTANICAL_RESERVE("Botanical reserve", 78, DigSite.DAEMONHEIM, "Castle"),
    COMMUNAL_SPACE("Communal space", 87, DigSite.DAEMONHEIM, "Castle"),
    PROJECTION_SPACE("Projection space", 103, DigSite.DAEMONHEIM, "Depths"),
    SECURITY_BOOTH("Security booth", 107, DigSite.DAEMONHEIM, "Depths"),
    TRAVELLERS_STATION("Traveller's station", 113, DigSite.DAEMONHEIM, "Depths"),

    // Other locations
    CENTURION_REMAINS("Centurion remains", 1, DigSite.OTHER, "Archaeology Guild"),
    MYSTERY_REMAINS("Mystery remains", 1, DigSite.OTHER, "Harvest Hollow Graveyard"),
    CRYSTALLISED_RUBBLE("Crystallised rubble", 30, DigSite.OTHER, ""),
    FORUM_ENTRANCE("Forum entrance", 74, DigSite.OTHER, ""),
    RUNIC_DEBRIS("Runic debris", 86, DigSite.OTHER, ""),
    STANDING_STONE_DEBRIS("Standing stone debris", 86, DigSite.OTHER, ""),
    ;

    override fun toString() = "$objectName - level $level"

    companion object {
        /** The site's hotspots, lowest level first, as the config dropdown wants them. */
        fun inSite(site: DigSite) = entries.filter { it.digSite == site }.sortedBy { it.level }.toTypedArray()
    }
}
