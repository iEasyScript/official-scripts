package com.projectx.script.impl.archaeology

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.api.equipment
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.findClosestObjectToTile
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.script.api.webWalk
import world.gregs.voidps.type.Tile
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Getting to where the work is.
 *
 * Two hops cover the world. The Archaeology journal teleports to the guild, and the dig sites map on the
 * guild wall fast travels out to any dig site the account has unlocked. Nothing else reaches most of them:
 * they are islands, dungeons and a floating citadel that no amount of walking will get to.
 *
 * Inside a site the script learns rather than guesses. The first time it stands at a hotspot it writes down
 * the tile, and from then on it goes straight back - across restarts, because the note is kept on disk
 * beside the engine's own script data. A hotspot it has not seen yet is found by sweeping outwards from
 * where the fast travel dropped it; one it cannot find is handed back to the planner, which picks another
 * rather than stalling.
 *
 * A site the account has not unlocked refuses to travel, and that refusal is the answer the planner wants -
 * far better than a level check that a quest requirement would make wrong.
 */
internal object ArchTravel {

    /** Where the guild's facilities are; everything on the campus is a short walk from here. */
    val GUILD_CENTRE: Tile = Tile.of(3355, 3392, 0)

    private val store = File(
        System.getProperty("user.home"),
        ".projectx/script-data/archaeology-hotspots.txt",
    )

    /**
     * Hotspot name to the obstacles that reach it from the site's arrival point.
     *
     * A tile alone is not enough for anything behind a door: the tile is on the far side of it. So the way
     * through is kept too, and replayed before the walk. Saved with the tiles, so it survives a restart.
     */
    private val routes = mutableMapOf<String, List<RouteStep>>()

    fun rememberRoute(hotspot: Hotspot, route: List<RouteStep>) {
        if (route.isEmpty()) {
            routes.remove(hotspot.name)
        } else {
            routes[hotspot.name] = route.toList()
        }
        save()
    }

    fun routeTo(hotspot: Hotspot): List<RouteStep> = routes[hotspot.name].orEmpty()

    /** Hotspot object id to the tile it was last seen at. Loaded once, then written through on change. */
    private val learnedTiles: MutableMap<Int, Tile> by lazy { load() }

    fun remember(obj: SceneObject) {
        val known = learnedTiles[obj.id]
        if (known != null && known.x == obj.tile.x && known.y == obj.tile.y && known.plane == obj.tile.plane) return
        learnedTiles[obj.id] = obj.tile
        save()
    }

    fun learnedTile(hotspot: Hotspot): Tile? {
        for (id in hotspot.objectIds) learnedTiles[id]?.let { return it }
        return null
    }

    fun forget(hotspot: Hotspot) {
        val hadTile = hotspot.objectIds.any { learnedTiles.containsKey(it) }
        val hadRoute = routes.containsKey(hotspot.name)
        if (!hadTile && !hadRoute) return
        hotspot.objectIds.forEach { learnedTiles.remove(it) }
        routes.remove(hotspot.name)
        save()
    }

    /**
     * True when this scenery is the hotspot's.
     *
     * Many hotspots are multi-locs: the placed object is an unnamed shell whose id is nothing the data records,
     * and a varbit transforms it into the real thing. [SceneObject.id] is that shell, so matching on it alone
     * misses every hotspot built that way - the ids in [Hotspot.objectIds] are the transformed ones. The
     * resolved id is what to compare, with the raw id kept for the hotspots that are plain scenery.
     */
    private fun Hotspot.isScenery(obj: SceneObject): Boolean =
        objectIds.any { it == obj.visibleTypeId || it == obj.id }

    fun findHotspot(hotspot: Hotspot, range: Int = SCENE_RANGE): SceneObject? =
        findClosestObject(range) { obj -> hotspot.isScenery(obj) && obj.hasOption("Excavate") }

    /**
     * The hotspot's scenery nearest [from] rather than nearest the player.
     *
     * A dig site lays the same hotspot out as several patches, so when a time sprite settles on one of them this
     * is how the dig aims at that patch instead of whichever happens to be underfoot.
     */
    fun findHotspotNear(hotspot: Hotspot, from: Tile, range: Int = SCENE_RANGE): SceneObject? =
        findClosestObjectToTile(from, range) { obj -> hotspot.isScenery(obj) && obj.hasOption("Excavate") }

    /**
     * The hotspot while nobody has dug it yet, which is why [findHotspot] cannot see it.
     *
     * Until it is uncovered the same shell transforms to the dig site's soil instead - "Aerated sediment",
     * "Ancient gravel" - which offers Uncover and carries none of the hotspot's own identity. What does give it
     * away is the shell's transform list: it still holds the id the hotspot will become, so a patch of soil can
     * be told apart from its neighbours and the right one uncovered rather than the nearest.
     */
    fun findBuriedHotspot(hotspot: Hotspot, range: Int = SCENE_RANGE): SceneObject? =
        findClosestObject(range) { obj ->
            obj.hasOption(UNCOVER) &&
                obj.defs.transforms?.any { into -> hotspot.objectIds.any { it == into } } == true
        }

    /** Any un-dug hotspot at all, for when the wanted one cannot be told apart from its neighbours. */
    fun findAnyBuriedHotspot(range: Int = SCENE_RANGE): SceneObject? =
        findClosestObject(range) { it.hasOption(UNCOVER) }

    const val UNCOVER = "Uncover"

    fun hasJournal(): Boolean = inventory.hasItem(ArchIds.JOURNAL) || equipment.hasItem(ArchIds.JOURNAL)

    fun siteMapOpen(): Boolean = interfaces.getComponent(ArchIds.SITE_MAP, 0)?.visible == true

    fun atGuild(): Boolean = localPlayer.tile.withinDistance(GUILD_CENTRE, GUILD_RADIUS)

    /** How many hotspots this account has taught the script the way to, for the overlay. */
    fun learnedCount(): Int = learnedTiles.size

    /**
     * Which dig site the player is standing in, judged by the excavation scenery around them, or 0 for none.
     *
     * This is what makes "walk me to a dig site and press start" work: the script does not have to be told
     * where it is, it reads it off the world, and the planner then prefers hotspots it can walk to from here.
     */
    fun currentSiteIndex(): Int {
        val here = findClosestObject(SCENE_RANGE) { obj -> obj.hasOption("Excavate") } ?: return 0
        return ArchData.hotspots
            .firstOrNull { it.objectIds.any { id -> id == here.visibleTypeId || id == here.id } }
            ?.siteIndex ?: 0
    }

    private fun load(): MutableMap<Int, Tile> {
        val map = mutableMapOf<Int, Tile>()
        if (!store.isFile) return map
        runCatching {
            store.forEachLine { line ->
                val parts = line.trim().split(',')
                if (parts.size != 4) return@forEachLine
                val values = parts.map { it.trim().toIntOrNull() ?: return@forEachLine }
                map[values[0]] = Tile.of(values[1], values[2], values[3])
            }
        }.onFailure { println("[Archaeology] Could not read remembered hotspots: ${it.message}") }
        return map
    }

    private fun save() {
        runCatching {
            store.parentFile?.mkdirs()
            store.writeText(
                learnedTiles.entries.joinToString("\n") { (id, tile) ->
                    "$id,${tile.x},${tile.y},${tile.plane}"
                },
            )
        }.onFailure { println("[Archaeology] Could not save remembered hotspots: ${it.message}") }
    }

    const val SCENE_RANGE = 55
    const val GUILD_RADIUS = 70
    const val INTERFACE_TIMEOUT = 6_000L
    const val TELEPORT_TIMEOUT = 20_000L

    /**
     * How long to let a teleport finish arriving after the coordinates have already changed.
     *
     * Generous on purpose: overshooting costs a few idle ticks, while undershooting sends the walker off
     * before the destination scene exists, which is far more expensive to recover from.
     */
    const val ARRIVAL_TIMEOUT = 12_000L
    const val WALK_TIMEOUT = 20_000L
    const val TAU = 2 * Math.PI
    const val SWEEP_POINTS = 8
    val SWEEP_RADII = intArrayOf(16, 30, 46)
}

/**
 * Waits for a teleport that has already fired to actually finish putting the player down.
 *
 * The coordinates change the instant a teleport starts, several ticks before the player is standing still in
 * the destination scene. Treating that first change as "arrived" is what sent the walker off too early: at
 * Kharid-et it would plan a route while the fort was still loading in, find none of the scene it needed, and
 * walk at the outside of the fort instead of round to the entrance.
 *
 * So the arrival is waited out in two parts - reaching the tile the site drops you on, then the player going
 * quiet - rather than by sleeping for a fixed time and hoping. [expected] is the site's known arrival tile,
 * or null for a teleport with no fixed destination, which just waits for the player to settle.
 */
internal suspend fun Script.awaitArrival(expected: Tile?): Boolean {
    if (expected != null) {
        delayUntil(ArchTravel.ARRIVAL_TIMEOUT) { localPlayer.tile.withinDistance(expected, SAME_SITE) }
    }

    // Standing still is not enough on its own: the tile stops changing for a moment mid-teleport too, so the
    // player also has to have stopped animating before the scene around them can be trusted.
    var previous: Tile? = null
    delayUntil(ArchTravel.ARRIVAL_TIMEOUT) {
        val now = localPlayer.tile
        val still = previous == now && !localPlayer.isAniMoving
        previous = now
        still
    }

    val settled = !localPlayer.isAniMoving
    if (!settled) println("[Archaeology] Teleport did not settle in time; continuing from ${localPlayer.tile}")
    return settled
}

/**
 * Walks to [tile], close enough that whatever stands there is in the loaded scene. A short hop is walked
 * directly; anything further goes through the web walker, which will use a lodestone when that is quicker.
 */
internal suspend fun Script.walkNear(tile: Tile): Boolean {
    if (localPlayer.tile.withinDistance(tile, ARRIVED)) return true

    // Only a short hop on the same floor is worth a single click. Anything further goes through the web
    // walker, which paths around walls and through doors, stairs and shortcuts; a click does none of that, so
    // it used to walk into the side of Kharid-et's fort rather than round to the entrance, time out, and leave
    // the explorer thinking that entrance could not be reached.
    if (tile.plane == localPlayer.tile.plane && localPlayer.tile.withinDistance(tile, SHORT_HOP)) {
        walkTo(tile.randomize(2), true)
        delayUntil(ArchTravel.WALK_TIMEOUT) { localPlayer.tile.withinDistance(tile, ARRIVED) }
        if (localPlayer.tile.withinDistance(tile, ARRIVED + 2)) return true
    }

    webWalk(tile, arriveDistance = ARRIVED)
    return localPlayer.tile.withinDistance(tile, ARRIVED + 2)
}

/**
 * Teleports to the Archaeology Guild with the journal, which is the one travel the journal actually does.
 *
 * The backpack copy is used: the worn option needs the equipment tab to be the open side panel, which
 * nothing in a run guarantees, so that is only ever a fallback.
 */
internal suspend fun Script.teleportToGuild(): Boolean {
    if (ArchTravel.atGuild()) return true
    val from = localPlayer.tile
    val clicked = inventory.clickItem(ArchIds.JOURNAL, "Teleport") ||
        equipment.clickItem(ArchIds.JOURNAL, "Teleport")
    if (!clicked) return walkNear(ArchTravel.GUILD_CENTRE)
    delayUntil(ArchTravel.TELEPORT_TIMEOUT) {
        ArchTravel.atGuild() || !localPlayer.tile.withinDistance(from, 12)
    }
    awaitArrival(ArchTravel.GUILD_CENTRE)
    return ArchTravel.atGuild()
}

/**
 * Gets back to a hotspot the script has stood at before.
 *
 * A remembered tile is only walkable from the same dig site, so this fast travels there first whenever the
 * player is somewhere else. Returns false when the hotspot has never been seen or the walk could not get
 * there - both of which the planner reads as "choose somewhere else".
 */
internal suspend fun Script.returnToHotspot(hotspot: Hotspot): Boolean {
    val known = ArchTravel.learnedTile(hotspot) ?: return false
    if (ArchTravel.findHotspot(hotspot) != null) return true

    val route = ArchTravel.routeTo(hotspot)
    if (!localPlayer.tile.withinDistance(known, SAME_SITE) || route.isNotEmpty()) {
        val site = ArchData.digSite(hotspot.siteIndex) ?: return false
        if (!fastTravelTo(site)) return false
    }
    if (route.isNotEmpty() && replayRoute(hotspot, route) != null) return true
    if (!walkNear(known)) return false
    return ArchTravel.findHotspot(hotspot) != null
}

/**
 * Fast travels to a dig site through the dig sites map on the wall of the guild hall.
 *
 * This is the only thing in the game that will carry you to most dig sites, so it is the whole of the
 * script's reach: journal home to the guild, map out to the site. A site the account has not unlocked simply
 * does not travel, which is how the planner learns to stop choosing it.
 */
internal suspend fun Script.fastTravelTo(site: DigSite): Boolean {
    ArchIds.SITE_ARRIVAL[site.mapIndex]?.let { arrival ->
        if (localPlayer.tile.withinDistance(arrival, SAME_SITE)) return true
    }
    if (!teleportToGuild()) return false
    if (!openDigSitesMap()) return false

    val from = localPlayer.tile
    delay(620, 260)
    // Icons sit in the order the sites are listed, which is the site's map slot counted from zero.
    val travelled = IFSlot(ArchIds.SITE_MAP, ArchIds.SITE_MAP_ICONS, site.mapIndex - 1)
        .click(ArchIds.SITE_MAP_FAST_TRAVEL_OP)
    if (travelled) {
        delayUntil(ArchTravel.TELEPORT_TIMEOUT) {
            !localPlayer.tile.withinDistance(from, 20) && !ArchTravel.atGuild()
        }
    }
    val moved = !localPlayer.tile.withinDistance(from, 20) && !ArchTravel.atGuild()
    if (!moved) {
        closeDigSitesMap()
        return false
    }

    // The teleport has fired, which is not the same as having arrived. Everything after this plans routes
    // through the scene the player lands in, so it has to be the real one.
    awaitArrival(ArchIds.SITE_ARRIVAL[site.mapIndex])
    return true
}

private suspend fun Script.openDigSitesMap(): Boolean {
    if (ArchTravel.siteMapOpen()) return true
    if (!walkNear(ArchIds.DIG_SITES_MAP_TILE)) return false
    val map = findClosestObject(MAP_RANGE) {
        it.id == ArchIds.DIG_SITES_MAP_OBJECT && it.hasOption("View")
    } ?: return false
    if (!map.interact("View")) return false
    delayUntil(ArchTravel.INTERFACE_TIMEOUT) { ArchTravel.siteMapOpen() }
    return ArchTravel.siteMapOpen()
}

private suspend fun Script.closeDigSitesMap() {
    if (!ArchTravel.siteMapOpen()) return
    IFSlot(ArchIds.SITE_MAP, ArchIds.SITE_MAP_CLOSE, -1).click(1)
    delayUntil(ArchTravel.INTERFACE_TIMEOUT) { !ArchTravel.siteMapOpen() }
}

/**
 * Walks a widening ring around the player looking for [hotspot]'s scenery.
 *
 * This is how a hotspot gets found the first time, when the player has been dropped somewhere in the right
 * dig site. The sweep is bounded, so a hotspot behind a door or down a staircase fails in a known time
 * rather than walking forever, and the planner then picks another one instead of the run stalling.
 */
internal suspend fun Script.sweepForHotspot(hotspot: Hotspot): SceneObject? {
    ArchTravel.findHotspot(hotspot)?.let { ArchTravel.remember(it); return it }
    val origin = localPlayer.tile
    for (radius in ArchTravel.SWEEP_RADII) {
        for (step in 0 until ArchTravel.SWEEP_POINTS) {
            val angle = ArchTravel.TAU * step / ArchTravel.SWEEP_POINTS
            val target = Tile.of(
                origin.x + (radius * cos(angle)).toInt(),
                origin.y + (radius * sin(angle)).toInt(),
                origin.plane,
            )
            if (!walkTo(target, true)) continue
            delayUntil(ArchTravel.WALK_TIMEOUT) {
                localPlayer.tile.withinDistance(target, 6) || ArchTravel.findHotspot(hotspot) != null
            }
            ArchTravel.findHotspot(hotspot)?.let { ArchTravel.remember(it); return it }
        }
    }
    return null
}

/** Close enough to count as being at the same dig site, so travel is not repeated on arrival. */
private const val SAME_SITE = 120
private const val MAP_RANGE = 15
private const val ARRIVED = 6

/** Far enough that one click is still sensible; past this the web walker earns its planning. */
private const val SHORT_HOP = 12
