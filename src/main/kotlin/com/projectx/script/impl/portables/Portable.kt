package com.projectx.script.impl.portables

import com.projectx.game.nxt.entity.location.SceneObject
import org.projectx.core.game.skill.Skill

/**
 * One job a portable can be set to, which is one entry on its menu.
 *
 * The wording is read off the objects themselves rather than guessed. Matching is exact, so "Mix" does not
 * find the well's "Mix Potions" and "Add-logs" does not find the brazier's "Add logs" - both of which it
 * originally did not.
 *
 * [skill] is what the job trains, which is not always the station's: bones on a brazier are cremated, and
 * the experience that says the run is working is Prayer's rather than Firemaking's.
 *
 * [opensMakeWindow] is false for the brazier, where logs and bones go on and the player burns them where
 * they stand, with no list to pick from. Everything else opens the ordinary make window and waits to be told
 * what to make.
 */
enum class PortableJob(
    private val label: String,
    val option: String,
    val skill: Skill,
    val opensMakeWindow: Boolean = true,
) {
    CONSTRUCT("Construct", "Construct", Skill.CONSTRUCTION),
    FLETCH("Fletch", "Fletch", Skill.FLETCHING),
    AMMO("Ammo", "Ammo", Skill.FLETCHING),
    STRING("String", "String", Skill.FLETCHING),
    COOK("Cook", "Cook", Skill.COOKING),
    MIX_POTIONS("Mix potions", "Mix Potions", Skill.HERBLORE),
    CRAFT("Craft", "Craft", Skill.CRAFTING),
    CUT_GEMS("Cut gems", "Cut Gems", Skill.CRAFTING),
    CLAY_CRAFTING("Clay crafting", "Clay Crafting", Skill.CRAFTING),
    TAN_LEATHER("Tan leather", "Tan Leather", Skill.CRAFTING),
    ADD_LOGS("Burn logs", "Add logs", Skill.FIREMAKING, opensMakeWindow = false),
    ADD_BONES("Cremate bones", "Add bones", Skill.PRAYER, opensMakeWindow = false);

    override fun toString() = label
}

/**
 * A portable skilling station, and the jobs it can be set to.
 *
 * Each one is several scenery ids rather than one: a portable is placed by a player, and which id it
 * appears as depends on the left-click option its owner configured. Every variant carries the same menu in a
 * different order, so a station is recognised by any of its ids and a job is found by its wording, never by
 * where it sits on the menu.
 *
 * [jobs] lists every menu entry that is a skilling job, first being the default. The well's "Take Vials",
 * and every station's "Configure" and "Examine", are left out: none of them makes anything.
 */
enum class Portable(
    private val label: String,
    val objectIds: IntArray,
    val jobs: List<PortableJob>,
) {
    WORKBENCH("Workbench", intArrayOf(117926), listOf(PortableJob.CONSTRUCT)),
    FLETCHER(
        "Fletcher",
        intArrayOf(106598, 106599, 106600),
        listOf(PortableJob.FLETCH, PortableJob.AMMO, PortableJob.STRING),
    ),
    RANGE("Range", intArrayOf(89768), listOf(PortableJob.COOK)),
    WELL("Well", intArrayOf(89770), listOf(PortableJob.MIX_POTIONS)),
    CRAFTER(
        "Crafter",
        intArrayOf(106594, 106595, 106596, 106597),
        listOf(PortableJob.CRAFT, PortableJob.CUT_GEMS, PortableJob.CLAY_CRAFTING, PortableJob.TAN_LEATHER),
    ),
    BRAZIER("Brazier", intArrayOf(106601, 106602), listOf(PortableJob.ADD_LOGS, PortableJob.ADD_BONES));

    override fun toString() = label

    fun matches(obj: SceneObject): Boolean =
        objectIds.any { it == obj.id || it == obj.visibleTypeId }
}
