package com.projectx.script.impl.tools

import com.projectx.game.interfaces.IFSlot
import com.projectx.game.nxt.DoActionOpcode
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.localPlayer
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import world.gregs.voidps.type.Tile
import java.io.File

/**
 * Watches you use ways through the world and writes down where each one put you.
 *
 * Every link in the web walker has to come from somewhere, and the part a cache cannot give is the
 * destination: the game files name a fort entrance and list its options, but where "Main fortress" lands you
 * is a decision the server makes and nothing local knows until it happens. So it is watched for instead.
 *
 * Run this and play. Each time you use an object and end up somewhere you were not, that is written to
 * `~/.projectx/webwalker/recorded-links.json` as a candidate, with the option you picked and the tiles either
 * side. The file is appended to across sessions and deduplicated, so mapping accumulates over days rather
 * than having to be done in one sitting.
 *
 * Candidates, not links: a recording is what happened once, to this account, with these levels and this quest
 * log. Whether it belongs in the walker is a separate judgement, which is why nothing here writes to
 * `links.json` directly.
 */
@ScriptDescription(
    name = "Link Recorder",
    version = "1.1.0",
    author = "Cryptic",
    description = "Records where the ways through the world put you, as web walker link candidates. Run it and play normally.",
)
class LinkRecorder : Script() {

    private data class Candidate(
        val objectId: Int,
        val typeId: Int,
        val name: String,
        val option: String,
        val objectTile: Tile,
        val from: Tile,
        val to: Tile,
        val steps: List<IFSlot>,
    )

    /**
     * What was clicked, and how far along we are in watching what it does.
     *
     * Using something across the room happens in two parts - the walk to it, then the thing itself - and
     * only the second is a link. So the walk is waited out first: [standing] stays null until we are stood
     * still at the object, and becomes the tile we were stood on, which is where the link starts.
     */
    private class Pending(val obj: SceneObject, val option: String, val at: Long) {
        var standing: Tile? = null
        var idleSince: Long = 0

        /** Interface clicks made while using it, in order - the panel of destinations and which was picked. */
        val steps = mutableListOf<IFSlot>()
    }

    private val recorded = LinkedHashSet<String>()
    private var pending: Pending? = null

    /**
     * The last tile we were stood still on. Not the current tile: while walking to an obstacle the current
     * tile changes every step, and the tile that belongs in a link is the one we were on when we used the
     * thing - which is wherever the walk finished.
     */
    private var lastStable: Tile? = null
    private var previous: Tile? = null
    private var written = 0

    override fun onStart() {
        store().parentFile?.mkdirs()
        loadExisting()
        println("[LinkRecorder] Recording to ${store().absolutePath}")
        println("[LinkRecorder] ${recorded.size} already recorded. Use doors, stairs, shortcuts and teleports as you normally would.")
    }

    override fun onEvent(event: Event) {
        if (event !is ManualDoAction) return

        // A way through is not always a click and a wait. The dig sites map opens a panel and goes nowhere
        // until a destination is picked, and which one decides where you come out - so the clicks made while
        // using something are kept with it, in the order they happened.
        val slot = event.target as? IFSlot
        if (slot != null) {
            if (event.opcode !in COMPONENT_OPTIONS) return
            pending?.let { it.steps += slot; it.idleSince = System.currentTimeMillis() }
            return
        }

        val index = OBJECT_OPTIONS.indexOf(event.opcode)
        if (index < 0) return
        val obj = event.target as? SceneObject ?: return
        // The opcode says which menu row was taken; the cache says what that row is called, and the name is
        // what a link has to carry because that is what the walker will click.
        val option = obj.getDef().getOption(index) ?: return
        pending = Pending(obj, option, System.currentTimeMillis())
    }

    override suspend fun loop() {
        val now = localPlayer.tile
        val busy = localPlayer.isAniMoving
        val settled = now == previous && !busy
        previous = now

        val use = pending
        if (use == null) {
            delay(POLL, POLL / 2)
            return
        }

        val standing = use.standing
        if (standing == null) {
            // Still getting there. A click can be made from across the room, so this is given room to walk.
            if (settled && now.withinDistance(use.obj.tile, USED_FROM)) {
                use.standing = now
                use.idleSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - use.at > APPROACH_WINDOW) {
                pending = null
            }
            delay(POLL, POLL / 2)
            return
        }

        // Stood at it. Whatever it does to us happens now, so the next place we come to rest is the
        // destination - however near, which matters because a rubble moves you two tiles and a lift moves
        // you across the world, and both are links.
        if (settled && now != standing) {
            pending = null
            record(Candidate(use.obj.id, use.obj.visibleTypeId, use.obj.name(), use.option, use.obj.tile, standing, now, use.steps.toList()))
            delay(POLL, POLL / 2)
            return
        }

        // Nothing is happening to us. A lift takes its time, so the wait is patient while the player is
        // animating or moving and only runs down while they are stood there idle - which is what tells a
        // way through that is working from one that was never a way through, like a map you just opened.
        if (busy) use.idleSince = System.currentTimeMillis()
        // Reading a panel of destinations is done standing still, so a click on one buys more time.
        val window = if (use.steps.isEmpty()) SETTLE_WINDOW else INTERFACE_WINDOW
        if (System.currentTimeMillis() - use.idleSince > window) pending = null
        delay(POLL, POLL / 2)
    }

    private fun record(c: Candidate) {
        val key = "${c.typeId}|${c.option}|${c.from.x},${c.from.y},${c.from.plane}|${c.to.x},${c.to.y},${c.to.plane}"
        if (!recorded.add(key)) return
        written++
        val via = if (c.steps.isEmpty()) "" else " via " + c.steps.joinToString(" then ") { "if(${it.interfaceId},${it.componentId},${it.slotId})" }
        println("[LinkRecorder] ${c.name} (${c.option}) ${c.from.x},${c.from.y},${c.from.plane} -> ${c.to.x},${c.to.y},${c.to.plane}$via")
        runCatching {
            store().appendText(
                """{"objectId":${c.objectId},"typeId":${c.typeId},"name":${quote(c.name)},"option":${quote(c.option)},""" +
                    """"objectTile":[${c.objectTile.x},${c.objectTile.y},${c.objectTile.plane}],""" +
                    """"from":[${c.from.x},${c.from.y},${c.from.plane}],"to":[${c.to.x},${c.to.y},${c.to.plane}]}""" + "\n",
            )
        }.onFailure { println("[LinkRecorder] Could not write: ${it.message}") }
    }

    /**
     * Read back what earlier runs found, so a way through recorded yesterday is not recorded again today.
     * Only the key matters, so the fields are picked out of the line rather than the whole thing parsed.
     */
    private fun loadExisting() {
        val file = store()
        if (!file.exists()) return
        runCatching {
            file.forEachLine { line ->
                val type = line.between("\"typeId\":", ",") ?: return@forEachLine
                val option = line.between("\"option\":\"", "\"") ?: return@forEachLine
                val from = line.between("\"from\":[", "]") ?: return@forEachLine
                val to = line.between("\"to\":[", "]") ?: return@forEachLine
                recorded.add("$type|$option|$from|$to")
            }
        }
    }

    private fun String.between(open: String, close: String): String? {
        val start = indexOf(open)
        if (start < 0) return null
        val from = start + open.length
        val end = indexOf(close, from)
        return if (end < 0) null else substring(from, end)
    }

    override fun onStop() = println("[LinkRecorder] Stopped. $written new this run, ${recorded.size} in total.")

    private fun store(): File =
        File(File(System.getProperty("user.home"), ".projectx/webwalker"), "recorded-links.json")

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        /** Menu rows one to six on a piece of scenery, in order, so the index is the option's place in the cache. */
        val OBJECT_OPTIONS = listOf(
            DoActionOpcode.OBJECT_1, DoActionOpcode.OBJECT_2, DoActionOpcode.OBJECT_3,
            DoActionOpcode.OBJECT_4, DoActionOpcode.OBJECT_5, DoActionOpcode.OBJECT_6,
        )

        /** How long to allow for walking to the thing before giving up on the click. */
        const val APPROACH_WINDOW = 20_000L

        /**
         * How long to keep watching once we are stood at it, counted only while the player is idle. A lift
         * animation does not run this down, because the player is busy for all of it; standing at something
         * that turned out not to move us does, which is how a click that was never a way through is dropped
         * instead of being pinned to wherever we wander next.
         */
        const val SETTLE_WINDOW = 3_000L

        /** The same, once an interface has been clicked - picking from a panel is slower than being moved. */
        const val INTERFACE_WINDOW = 12_000L

        /** The opcodes whose target is an interface component rather than something in the world. */
        val COMPONENT_OPTIONS = setOf(
            DoActionOpcode.COMPONENT, DoActionOpcode.COMPONENT_SIXPLUS, DoActionOpcode.SELECT_COMPONENT,
        )
        const val POLL = 150

        /**
         * How close to the object we must have been standing for a move to be that object's doing. Generous,
         * because a tile is a large object's south-west corner and you can be several tiles from it and still
         * be using it - the scaffold at Everlight is used from seven tiles away.
         */
        const val USED_FROM = 10
    }
}
