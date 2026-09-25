package com.projectx.script.impl.combat.raksha

internal object RakshaIds {
    const val BOSS = 27352
    const val BOSS_SUBDUED = 27353
    const val BOSS_DORMANT = 27351
    const val ANIMA_POOL = 27354
    const val SHADOW_MANIFESTATION = 27355
    const val SHADOW_ENERGY = 27356
    const val DEATH = 27299

    const val PORTAL_NAME = "Portal (Raksha)"
    const val SECURITY_GATE = 118556

    const val INSTAKILL_HIGHLIGHT = 2789
    const val SHADOW_FLOOR = 7407
    const val BOMB = 4566

    const val ANIM_ATTACK_RANGED = 33705
    const val ANIM_ATTACK_MAGIC = 33703
    const val ANIM_ATTACK_MELEE = 33702
    const val ANIM_TAIL_SWEEP_ESCAPE = 33706
    const val ANIM_TAIL_SWEEP_FREEDOM = 33707
    const val ANIM_BOMBS = 33709
    const val ANIM_SHADOW_BOMBARDMENT = 33720
    const val ANIM_INSTAKILL_BIND = 33718
    const val ANIM_DOME = 33711
    const val ANIM_CONJURE = 35502

    val SWEEP_ANIMS = setOf(ANIM_TAIL_SWEEP_ESCAPE, ANIM_TAIL_SWEEP_FREEDOM)

    val ANIM_NAMES = mapOf(
        ANIM_ATTACK_RANGED to "Ranged auto",
        ANIM_ATTACK_MAGIC to "Magic auto",
        ANIM_ATTACK_MELEE to "Melee auto",
        ANIM_TAIL_SWEEP_ESCAPE to "Tail sweep (Escape)",
        ANIM_TAIL_SWEEP_FREEDOM to "Tail sweep (Freedom)",
        ANIM_BOMBS to "Bombs",
        ANIM_SHADOW_BOMBARDMENT to "Shadow bombardment",
        ANIM_INSTAKILL_BIND to "Insta-kill bind",
        ANIM_DOME to "Detonation dome",
    )

    const val SIPHON_CHAT = "anchors you to the shadows"

    const val PHASE2_HP_SOLO = 600_000
    const val PHASE3_HP_SOLO = 400_000
    const val PHASE4_HP_SOLO = 200_000
    const val LUCK_RING_HP_SOLO = 50_000
    const val POOL_START_BELOW_HP_SOLO = 375_000
    const val POOL_SKIP_BELOW_HP_SOLO = 325_000
    const val MANIFESTATION_SKIP_BELOW_HP_SOLO = 230_000

    const val VULNERABILITY_BOMB = 48951
    const val BLUE_BLUBBER_JELLYFISH = 42267
    const val RIPPER_DEMON_POUCH = 49417
    const val RIPPER_DEMON_SCROLL = 49419
    const val TRAINING_DUMMY = 16027

    val ELDER_OVERLOAD = intArrayOf(49039, 49037, 49035, 49033, 49031, 49029)
    val ADRENALINE_POTIONS = intArrayOf(
        49079, 49081, 49083, 49085,
        39220, 39222, 39224, 39226, 39228, 39230,
    )
    val LUCK_RINGS = intArrayOf(39812, 44559, 39814, 44560)

    const val SCRIPTURE_OF_JAS = 51814
    const val SCRIPTURE_OF_FUL = 52494
    const val ERETHDORS_GRIMOIRE = 42787

    val LOOT_COMMONS = setOf(
        48201, 48769,
        44815, 44814, 57174,
        47298, 47299, 51103, 51104, 51105, 51106,
        1747, 1748, 42954, 48075, 48076, 989, 990, 54019, 566,
    )

    val UNIQUES = mapOf(
        51102 to "Broken shackle",
        51082 to "Fleeting boots",
        51086 to "Shadow spike",
        51094 to "Greater Ricochet ability codex",
        51096 to "Greater Chain ability codex",
        51098 to "Divert ability codex",
    )

    val LOOT = LOOT_COMMONS + UNIQUES.keys
}
