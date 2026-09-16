package com.projectx.script.impl.agility

import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.api.findClosestNPC
import com.projectx.script.api.findClosestObjectToTile
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

/** Where the player stands when a step's obstacle is next. */
sealed interface StandingArea {
    fun contains(tile: Tile): Boolean

    /** How far [tile] is from the area's centre, used to pick between areas that overlap. */
    fun distance(tile: Tile): Int
}

class Region(private val xs: IntRange, private val ys: IntRange, private val plane: Int) : StandingArea {
    override fun contains(tile: Tile) = tile.plane == plane && tile.x in xs && tile.y in ys

    override fun distance(tile: Tile) = chebyshev(tile.x, tile.y, (xs.first + xs.last) / 2, (ys.first + ys.last) / 2)
}

/** The tiles within [radius] of where the previous obstacle lands; for courses whose obstacles sit close together. */
class Spot(private val x: Int, private val y: Int, private val plane: Int, private val radius: Int = 4) : StandingArea {
    override fun contains(tile: Tile) = tile.plane == plane && distance(tile) <= radius

    override fun distance(tile: Tile) = chebyshev(tile.x, tile.y, x, y)
}

private fun chebyshev(x: Int, y: Int, otherX: Int, otherY: Int) = max(abs(x - otherX), abs(y - otherY))

sealed class Obstacle(val name: String, val option: String) {
    /** Where the obstacle stands, when it has a fixed tile. */
    abstract val tile: Tile?

    /** Clicks this obstacle; false when it is not loaded or does not offer [option] right now. */
    abstract fun interact(): Boolean

    /** An object found by its exact tile, so a second object of the same name nearby is never clicked instead. */
    class Object(name: String, option: String, x: Int, y: Int, plane: Int) : Obstacle(name, option) {
        override val tile: Tile = Tile.of(x, y, plane)

        fun matches(obj: SceneObject) =
            obj.tileX == tile.x && obj.tileY == tile.y && obj.plane == tile.plane && obj.name() == name && obj.hasOption(option)

        fun find(): SceneObject? = findClosestObjectToTile(tile, 1) { matches(it) }

        override fun interact() = find()?.interact(option) == true
    }

    class Npc(name: String, option: String, private val range: Int = 15) : Obstacle(name, option) {
        override val tile: Tile? = null

        fun matches(npc: NPC) = npc.name() == name && npc.hasOption(option)

        override fun interact() = findClosestNPC(range) { matches(it) }?.interact(option) == true
    }
}

/**
 * One obstacle in lap order and the region the player stands in when it is next. [shortcut], when set, is taken
 * instead whenever it is open.
 */
class Step(val obstacle: Obstacle, val takenFrom: StandingArea, val shortcut: Obstacle? = null)

enum class AgilityCourse(val label: String, private val anchor: Tile, val steps: List<Step>) {
    WILDERNESS(
        "Wilderness",
        Tile.of(3004, 3937, 0),
        listOf(
            Step(Obstacle.Object("Obstacle pipe", "Squeeze-through", 3004, 3938, 0), Region(2985..3012, 3920..3937, 0)),
            Step(Obstacle.Object("Ropeswing", "Swing-on", 3005, 3952, 0), Region(3002..3008, 3949..3955, 0)),
            Step(Obstacle.Object("Stepping stone", "Cross", 3001, 3960, 0), Region(3002..3008, 3956..3964, 0)),
            Step(Obstacle.Object("Log balance", "Walk-across", 3001, 3945, 0), Region(2990..2996, 3956..3964, 0)),
            Step(Obstacle.Object("Cliffside", "Climb", 2993, 3936, 0), Region(2988..2997, 3939..3950, 0)),
        ),
    ),
    HEFIN(
        "Hefin (Prifddinas)",
        Tile.of(2176, 3400, 1),
        listOf(
            Step(Obstacle.Object("Walkway", "Leap across", 2176, 3403, 1), Region(2168..2192, 3390..3402, 1)),
            Step(Obstacle.Object("Cliff", "Traverse", 2179, 3420, 1), Region(2176..2186, 3414..3424, 1)),
            Step(
                Obstacle.Object("Cathedral", "Scale", 2170, 3438, 1),
                Region(2166..2176, 3432..3442, 1),
                shortcut = Obstacle.Object("Window", "Leap through", 2172, 3436, 1),
            ),
            Step(Obstacle.Object("Roof", "Vault", 2178, 3447, 2), Region(2172..2183, 3444..3452, 2)),
            Step(Obstacle.Object("Zip line", "Slide down", 2188, 3442, 2), Region(2184..2192, 3438..3446, 2)),
            // The zip line lands on a platform whose only way off is merging with the light creature back to the start.
            Step(Obstacle.Npc("Light creature", "Merge with"), Region(2178..2192, 3404..3420, 2)),
        ),
    ),

    // Seven sections, each ending in a bonus; the last cliff face pays the lap bonus 60 tiles north of the start.
    ANACHRONIA(
        "Anachronia",
        Tile.of(5418, 2325, 0),
        listOf(
            Step(Obstacle.Object("Temple wall", "Traverse", 5415, 2324, 0), Spot(5428, 2383, 0)),
            Step(Obstacle.Object("Cliff face", "Traverse", 5411, 2325, 0), Spot(5414, 2324, 0)),
            Step(Obstacle.Object("Cliff face", "Traverse", 5408, 2324, 0), Spot(5410, 2325, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5394, 2320, 0), Spot(5408, 2323, 0)),
            Step(Obstacle.Object("Root", "Climb over", 5368, 2304, 0), Spot(5393, 2320, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5363, 2282, 0), Spot(5367, 2304, 0)),
            Step(Obstacle.Object("Cliff face", "Traverse", 5376, 2248, 0), Spot(5369, 2282, 0)),
            Step(Obstacle.Object("Tree", "Jump across", 5390, 2240, 0), Spot(5376, 2247, 0)),

            Step(Obstacle.Object("Cliff face", "Traverse", 5437, 2217, 0), Spot(5397, 2240, 0)),
            Step(Obstacle.Object("Ruined column", "Traverse", 5456, 2180, 0), Spot(5439, 2217, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5474, 2171, 0), Spot(5456, 2179, 0)),
            Step(Obstacle.Object("Plank", "Cross", 5483, 2171, 0), Spot(5475, 2171, 0)),
            Step(Obstacle.Object("Ruins", "Jump across", 5495, 2171, 0), Spot(5489, 2171, 0)),
            Step(Obstacle.Object("Ruins", "Traverse", 5524, 2182, 0), Spot(5502, 2171, 0)),

            Step(Obstacle.Object("Vines", "Cross", 5548, 2214, 0), Spot(5527, 2182, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5548, 2237, 0), Spot(5548, 2220, 0)),
            Step(Obstacle.Object("Root", "Traverse", 5553, 2246, 0), Spot(5548, 2244, 0)),
            Step(Obstacle.Object("Rock", "Traverse", 5563, 2272, 0), Spot(5553, 2249, 0)),
            Step(Obstacle.Object("Bones", "Climb over", 5577, 2289, 0), Spot(5565, 2272, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5581, 2295, 0), Spot(5578, 2289, 0)),
            Step(Obstacle.Object("Ruins", "Traverse", 5594, 2295, 0), Spot(5587, 2295, 0)),
            Step(Obstacle.Object("Block", "Climb over", 5627, 2287, 0), Spot(5596, 2295, 0)),

            Step(Obstacle.Object("Vines", "Cross", 5663, 2288, 0), Spot(5629, 2287, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5674, 2290, 0), Spot(5669, 2288, 0)),
            Step(Obstacle.Object("Roots", "Traverse", 5684, 2292, 0), Spot(5680, 2290, 0)),
            Step(Obstacle.Object("Roots", "Jump across", 5686, 2303, 0), Spot(5684, 2293, 0)),
            Step(Obstacle.Object("Big block", "Climb over", 5693, 2317, 0), Spot(5686, 2310, 0)),
            Step(Obstacle.Object("Sunken column", "Jump across", 5696, 2339, 0), Spot(5695, 2317, 0)),
            Step(Obstacle.Object("Roots", "Climb over", 5676, 2363, 0), Spot(5696, 2346, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5655, 2371, 0), Spot(5675, 2363, 0)),

            Step(Obstacle.Object("Vines", "Cross", 5653, 2399, 0), Spot(5655, 2377, 0)),
            Step(Obstacle.Object("Vines", "Cross", 5644, 2420, 0), Spot(5653, 2405, 0)),
            Step(Obstacle.Object("Roots", "Traverse", 5642, 2425, 0), Spot(5643, 2420, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5627, 2433, 0), Spot(5642, 2431, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5617, 2433, 0), Spot(5626, 2433, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5609, 2433, 0), Spot(5616, 2433, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5602, 2433, 0), Spot(5608, 2433, 0)),
            Step(Obstacle.Object("Cliff face", "Traverse", 5591, 2448, 0), Spot(5601, 2433, 0)),

            Step(Obstacle.Object("Bones", "Traverse", 5585, 2452, 0), Spot(5591, 2450, 0)),
            Step(Obstacle.Object("Spine", "Cross", 5575, 2453, 0), Spot(5584, 2452, 0)),
            Step(Obstacle.Object("Bones", "Traverse", 5565, 2452, 0), Spot(5574, 2453, 0)),
            Step(Obstacle.Object("Ruined temple", "Jump across", 5537, 2492, 0), Spot(5564, 2452, 0)),
            Step(Obstacle.Object("Ruined temple", "Climb", 5529, 2492, 0), Spot(5536, 2492, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5505, 2479, 0), Spot(5528, 2492, 0)),
            Step(Obstacle.Object("Ruined temple", "Jump across", 5505, 2469, 0), Spot(5505, 2478, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5505, 2463, 0), Spot(5505, 2468, 0)),

            Step(Obstacle.Object("Roots", "Cross", 5485, 2456, 0), Spot(5505, 2462, 0)),
            Step(Obstacle.Object("Cave entrance", "Enter", 5481, 2456, 0), Spot(5484, 2456, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5431, 2408, 0), Spot(5431, 2417, 0)),
            Step(Obstacle.Object("Ruined temple", "Traverse", 5425, 2398, 0), Spot(5431, 2407, 0)),
            Step(Obstacle.Object("Cliff face", "Traverse", 5426, 2388, 0), Spot(5425, 2397, 0)),
            Step(Obstacle.Object("Cliff face", "Traverse", 5428, 2384, 0), Spot(5426, 2387, 0)),
        ),
    );

    fun stepAt(tile: Tile): Step? = steps.filter { it.takenFrom.contains(tile) }.minByOrNull { it.takenFrom.distance(tile) }

    fun stepAfter(step: Step): Step = steps[(steps.indexOf(step) + 1) % steps.size]

    /** Close to the course's start or to any of its obstacles; Anachronia's lap spans hundreds of tiles. */
    fun isNear(tile: Tile) = (listOf(anchor) + steps.mapNotNull { it.obstacle.tile })
        .any { it.plane == tile.plane && chebyshev(tile.x, tile.y, it.x, it.y) <= NEAR_TILES }

    override fun toString() = label

    private companion object {
        const val NEAR_TILES = 60
    }
}
