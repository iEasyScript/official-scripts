package com.projectx.script.impl.portables

import com.projectx.game.nxt.entity.location.SceneObject
import org.projectx.core.game.skill.Skill

/**
 * A portable skilling station, and how to start it working.
 *
 * Each one is several scenery ids rather than one: a portable is placed by a player, and which id it
 * appears as depends on the variant that was dropped. A station is therefore recognised by any of its ids.
 *
 * [options] is a list because a portable offers several jobs and only one of them is this script's: a
 * crafter also cuts gems and tans leather, a fletcher also strings bows, a brazier also burns bones. The
 * first listed option the placed object carries is used, and anything not listed is passed over rather than
 * guessed at - which is what stops a run tanning leather because it could not find what it came for.
 *
 * The wording is read off the objects themselves rather than guessed. Matching is exact, so "Mix" does not
 * find the well's "Mix Potions" and "Add-logs" does not find the brazier's "Add logs" - both of which it
 * originally did not.
 */
enum class Portable(
    private val label: String,
    val skill: Skill,
    val objectIds: IntArray,
    val options: List<String>,
) {
    WORKBENCH("Workbench", Skill.CONSTRUCTION, intArrayOf(117926), listOf("Construct")),
    FLETCHER("Fletcher", Skill.FLETCHING, intArrayOf(106598, 106599), listOf("Fletch")),
    RANGE("Range", Skill.COOKING, intArrayOf(89768), listOf("Cook")),
    WELL("Well", Skill.HERBLORE, intArrayOf(89770), listOf("Mix Potions")),
    CRAFTER("Crafter", Skill.CRAFTING, intArrayOf(106594, 106595, 106596, 106597), listOf("Craft")),
    BRAZIER("Brazier", Skill.FIREMAKING, intArrayOf(106601, 106602), listOf("Add logs"));

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
