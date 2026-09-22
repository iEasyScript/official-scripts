package com.projectx.script.impl.portables

import com.projectx.game.nxt.entity.location.SceneObject
import org.projectx.core.game.skill.Skill

/**
 * A portable skilling station, and how to start it working.
 *
 * Each one is several scenery ids rather than one: a portable is placed by a player, and which id it
 * appears as depends on the variant that was dropped. A station is therefore recognised by any of its ids.
 *
 * [options] is a list rather than a name because the left-click option on a portable is configurable - a
 * station set to "Configure" offers different wording than one left alone - so the first option the placed
 * object actually carries is the one used. Anything not listed here is passed over rather than guessed at,
 * which is what stops the script clicking "Remove" on somebody's station.
 */
enum class Portable(
    private val label: String,
    val skill: Skill,
    val objectIds: IntArray,
    val options: List<String>,
) {
    WORKBENCH("Workbench", Skill.CONSTRUCTION, intArrayOf(117926), listOf("Build", "Make", "Craft")),
    FLETCHER("Fletcher", Skill.FLETCHING, intArrayOf(106599, 106598), listOf("Fletch", "Make", "Craft")),
    RANGE("Range", Skill.COOKING, intArrayOf(89768), listOf("Cook", "Cook-at", "Make")),
    WELL("Well", Skill.HERBLORE, intArrayOf(89770), listOf("Make", "Mix", "Brew", "Craft")),
    CRAFTER("Crafter", Skill.CRAFTING, intArrayOf(106595), listOf("Craft", "Make")),
    BRAZIER("Brazier", Skill.FIREMAKING, intArrayOf(106601, 106602), listOf("Burn", "Add-logs", "Use"));

    override fun toString() = label

    fun matches(obj: SceneObject): Boolean =
        objectIds.any { it == obj.id || it == obj.visibleTypeId }

    /** The option this placed station actually offers, or null when it offers none we know. */
    fun optionOn(obj: SceneObject): String? = options.firstOrNull { obj.hasOption(it) }

    /**
     * Whether the station finishes a job through the make interface.
     *
     * A brazier does not: logs go on and the player burns them where they stand, with no list to pick from.
     * Everything else opens the ordinary make window and waits to be told what to make.
     */
    val usesMakeInterface: Boolean get() = this != BRAZIER
}
