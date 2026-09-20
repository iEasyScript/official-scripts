package com.projectx.script.impl.archaeology

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.dialogueOptions
import com.projectx.script.api.getAllObjectsWithinRange
import com.projectx.script.api.interfaces
import com.projectx.script.api.localPlayer
import com.projectx.webwalker.WebLinks
import world.gregs.voidps.type.Tile

/**
 * Finding a hotspot that is not simply lying around where the fast travel drops you.
 *
 * Most hotspots are behind something: a fort door at Kharid-et, a lift and a secret passage at the Infernal
 * Source, scaffolding at Senntisten, a plank or a cliff at Everlight, a gap to walk across at Stormguard.
 * Sweeping in circles never reaches any of them, so this goes through the obstacles instead.
 *
 * It is a search, not a route table. From where it stands it walks the untried traversals in reach, uses
 * one, and looks again - depth first, bounded, never going back through the thing it just came out of. The
 * obstacles come from the cache ([ArchTraversal]), so agility obstacles and dungeon entrances are handled by
 * the same code and a new dig site needs no new route written by hand.
 *
 * The chain that worked is remembered, so the next trip replays it instead of searching again. That is what
 * turns a one-off exploration into a route the script keeps for good.
 */

/** One step of a remembered route: the scenery to use and the option to use on it. */
internal data class RouteStep(val objectId: Int, val option: String)

internal suspend fun Script.exploreForHotspot(hotspot: Hotspot): SceneObject? {
    ArchTravel.findHotspot(hotspot)?.let { return it }

    val used = mutableSetOf<Int>()
    val route = mutableListOf<RouteStep>()
    val found = descend(hotspot, used, route, depth = 0)
    if (found != null) {
        ArchTravel.rememberRoute(hotspot, route)
        ArchTravel.remember(found)
    }
    return found
}

/**
 * Walks one obstacle deeper, then recurses. Returns the hotspot's scenery once it is in the loaded scene.
 *
 * An obstacle that leads nowhere useful still costs its walk, so the depth cap is what keeps a wrong turn
 * from becoming a tour of the dungeon. Obstacles named as exits are left until last, because most of them
 * lead back the way we came.
 */
private suspend fun Script.descend(
    hotspot: Hotspot,
    used: MutableSet<Int>,
    route: MutableList<RouteStep>,
    depth: Int,
): SceneObject? {
    if (depth >= MAX_DEPTH) return null

    for (obstacle in nearbyTraversals(used)) {
        val option = obstacle.traversalOption() ?: continue

        val before = localPlayer.tile
        println("[Archaeology] Trying ${obstacle.name()} ($option) at ${obstacle.tile.x},${obstacle.tile.y}")
        if (!walkNear(obstacle.tile)) continue
        // Where the obstacle was actually used from, which is what a link starts at; `before` is from further back.
        val standing = localPlayer.tile
        if (!obstacle.interact(option)) continue

        // A way in may ask where to go before it takes you anywhere, so that is answered before waiting to move.
        delayUntil(CHOICE_APPEARS) { interfaces.isOpen(CHOICE_DIALOG) || !localPlayer.tile.withinDistance(standing, MOVED) }
        chooseDestination(hotspot)

        // Only spent once it was actually used. Marking it before the walk meant one failed approach - the
        // fort entrance clicked at across a wall - burned that way through for the rest of the exploration.
        used += obstacle.id

        // Going through changes where we are, or at least what is around us; both are worth re-looking at.
        delayUntil(TRAVERSE_TIMEOUT) {
            !localPlayer.tile.withinDistance(before, MOVED) || ArchTravel.findHotspot(hotspot) != null
        }
        delay(900, 400)
        learnTraversal(obstacle.id, option, standing)

        route += RouteStep(obstacle.id, option)
        ArchTravel.findHotspot(hotspot)?.let { return it }
        sweepForHotspot(hotspot)?.let { return it }
        descend(hotspot, used, route, depth + 1)?.let { return it }
        route.removeLastOrNull()
    }
    return null
}

/**
 * The ways on that are in reach and have not been tried, best first.
 *
 * Anything carrying a traversal option counts, whether or not the cache had a name for it, so a morphed
 * fort door and an unnamed ladder are both candidates. Ones the cache does name as leading back out are
 * tried last, and the rest go nearest first.
 */
private fun nearbyTraversals(used: Set<Int>): List<SceneObject> =
    getAllObjectsWithinRange(EXPLORE_RANGE)
        .filter { it.id !in used && it.traversalOption() != null }
        .distinctBy { it.id }
        .sortedWith(
            compareBy(
                { known(it)?.outward == true },
                { it.tile.stepsFrom(localPlayer.tile) },
            ),
        )

/** The metadata for this scenery, looked up under its resolved type as well as its placed id. */
private fun known(obj: SceneObject): Traversal? =
    ArchTraversal.of(obj.visibleTypeId) ?: ArchTraversal.of(obj.id)

/** The first traversal option this scenery actually offers, or null when it is not a way anywhere. */
internal fun SceneObject.traversalOption(): String? =
    ArchTraversal.OPTIONS.firstOrNull { hasOption(it) }

/**
 * Replays a route that worked before: go through each obstacle in turn, then look for the hotspot.
 *
 * Returns false the moment a step cannot be taken, which sends the caller back to exploring rather than
 * leaving the player halfway through a dungeon with a stale plan.
 */
internal suspend fun Script.replayRoute(hotspot: Hotspot, route: List<RouteStep>): SceneObject? {
    for (step in route) {
        ArchTravel.findHotspot(hotspot)?.let { return it }
        val obstacle = findClosestObject(EXPLORE_RANGE) {
            it.id == step.objectId && it.hasOption(step.option)
        } ?: return null
        val before = localPlayer.tile
        if (!walkNear(obstacle.tile)) return null
        val standing = localPlayer.tile
        if (!obstacle.interact(step.option)) return null
        delayUntil(CHOICE_APPEARS) { interfaces.isOpen(CHOICE_DIALOG) || !localPlayer.tile.withinDistance(standing, MOVED) }
        chooseDestination(hotspot)
        delayUntil(TRAVERSE_TIMEOUT) {
            !localPlayer.tile.withinDistance(before, MOVED) || ArchTravel.findHotspot(hotspot) != null
        }
        delay(800, 350)
        learnTraversal(step.objectId, step.option, standing)
    }
    return ArchTravel.findHotspot(hotspot) ?: sweepForHotspot(hotspot)
}

/**
 * Answers a way in that asks where to go rather than simply putting the player through.
 *
 * Kharid-et's fort entrance opens a "Choose destination." list - "1. Main fortress", "2. Prison block" - and
 * until it is answered the player stays outside, which is what left the explorer walking in circles around a
 * fort it had already clicked.
 *
 * Which answer is right depends on the part of the site the hotspot sits in, and the wording does not always
 * match the site's own name: the Carcerem is offered as the prison block. So the few that differ are named in
 * [DESTINATIONS] and anything else takes the first option, which is the way to the bulk of a site.
 *
 * A list whose wording is not known yet is logged rather than guessed at silently, so the next run says what
 * it saw and the mapping can be filled in.
 */
private suspend fun Script.chooseDestination(hotspot: Hotspot): Boolean {
    if (!interfaces.isOpen(CHOICE_DIALOG)) return false
    // Empty rows still carry their number, so "3." and the like are not real choices.
    val options = dialogueOptions.filterKeys { it.substringAfter('.').isNotBlank() }
    if (options.isEmpty()) return false

    val named = DESTINATIONS.entries.firstOrNull { hotspot.subSite.contains(it.key, ignoreCase = true) }?.value
    val chosen = named?.let { want -> options.keys.firstOrNull { it.contains(want, ignoreCase = true) } }
        ?: options.keys.first()

    if (named == null || !chosen.contains(named, ignoreCase = true)) {
        println("[Archaeology] No known destination for ${hotspot.subSite}; taking \"$chosen\" from ${options.keys}")
    }
    options[chosen]?.dialogueContinue()
    delayUntil(CHOICE_TIMEOUT) { !interfaces.isOpen(CHOICE_DIALOG) }
    delay(900, 400)
    return !interfaces.isOpen(CHOICE_DIALOG)
}

/** Parts of a dig site whose entrance offers them under another name. */
private val DESTINATIONS = mapOf("Carcerem" to "Prison block")

/**
 * Hands a traversal that worked to the web walker, so the next route is planned straight through it instead of
 * being explored for again.
 *
 * [standing] is the tile the obstacle was used from and wherever the player is now is where it came out, which is
 * exactly a link. Only recorded when it actually moved the player, and never from an instance, where the
 * coordinates mean nothing outside that copy of the area.
 */
private fun learnTraversal(objectId: Int, option: String, standing: Tile) {
    val arrived = localPlayer.tile
    if (standing.x >= INSTANCE_MIN_X || arrived.x >= INSTANCE_MIN_X) return
    val moved = arrived.plane != standing.plane || !arrived.withinDistance(standing, MOVED)
    if (!moved) return
    if (WebLinks.registerObjectLink(
            standing.x, standing.y, standing.plane,
            arrived.x, arrived.y, arrived.plane,
            objectId = objectId,
            action = option,
            costTiles = TRAVERSAL_COST_TILES,
        )
    ) {
        println("[Archaeology] Taught the walker: $option $objectId, ${standing.x},${standing.y},${standing.plane} -> ${arrived.x},${arrived.y},${arrived.plane}")
    }
}

/**
 * Chebyshev distance, counting another floor as far away.
 *
 * Tile's own distanceTo returns -1 across planes, which sorts a staircase on the floor above ahead of the door
 * beside you. Now that the scene can hold obstacles on more than one plane that matters, so it is spelled out.
 */
private fun Tile.stepsFrom(other: Tile): Int =
    if (plane != other.plane) Int.MAX_VALUE else maxOf(kotlin.math.abs(x - other.x), kotlin.math.abs(y - other.y))

/** Deep enough for a lift, a passage and a door; shallow enough that a wrong turn ends quickly. */
private const val MAX_DEPTH = 4
private const val EXPLORE_RANGE = 40
private const val TRAVERSE_TIMEOUT = 14_000L

/** The "Choose destination." list some ways in put up instead of moving you. */
private const val CHOICE_DIALOG = 720
private const val CHOICE_APPEARS = 3_000L
private const val CHOICE_TIMEOUT = 8_000L
private const val MOVED = 4

/** A lift or a door is a step or two of walking; priced so a route still prefers open ground when there is some. */
private const val TRAVERSAL_COST_TILES = 3

/** Instanced copies start here; their coordinates mean nothing to a route planned for the real world. */
private const val INSTANCE_MIN_X = 6400
