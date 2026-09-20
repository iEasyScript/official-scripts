package com.projectx.script.impl.tools

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
    version = "1.0.0",
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
    )

    /** What was clicked and when, waiting to see whether it moves us. */
    private class Pending(val obj: SceneObject, val option: String, val at: Long)

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
        val settled = now == previous && !localPlayer.isAniMoving
        previous = now

        if (!settled) {
            delay(POLL, POLL / 2)
            return
        }

        val before = lastStable
        lastStable = now
        if (before == null || before == now) {
            delay(POLL, POLL / 2)
            return
        }

        val use = pending
        if (use == null) {
            delay(POLL, POLL / 2)
            return
        }
        if (System.currentTimeMillis() - use.at > ATTRIBUTION_WINDOW) {
            pending = null
            delay(POLL, POLL / 2)
            return
        }

        // Clicking something across the room settles us twice: once where the walk to it finishes, and again
        // where the thing itself puts us. Only the second is a link, and what tells them apart is where we
        // started - you have to be stood at a scaffold to skip over it, so a move that began far from the
        // object is the walk there and not the way through. The click is kept for the move that follows.
        if (before.withinDistance(use.obj.tile, USED_FROM)) {
            pending = null
            record(Candidate(use.obj.id, use.obj.visibleTypeId, use.obj.name(), use.option, use.obj.tile, before, now))
        }
        delay(POLL, POLL / 2)
    }

    private fun record(c: Candidate) {
        val key = "${c.typeId}|${c.option}|${c.from.x},${c.from.y},${c.from.plane}|${c.to.x},${c.to.y},${c.to.plane}"
        if (!recorded.add(key)) return
        written++
        println("[LinkRecorder] ${c.name} (${c.option}) ${c.from.x},${c.from.y},${c.from.plane} -> ${c.to.x},${c.to.y},${c.to.plane}")
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

        /**
         * How long after a click a move still counts as that click's doing. Long enough to cover walking the
         * length of a room first, short enough that wandering off afterwards is not mistaken for a traversal.
         */
        const val ATTRIBUTION_WINDOW = 20_000L
        const val POLL = 150

        /**
         * How close to the object we must have been standing for a move to be that object's doing. Generous,
         * because a tile is a large object's south-west corner and you can be several tiles from it and still
         * be using it - the scaffold at Everlight is used from seven tiles away.
         */
        const val USED_FROM = 10
    }
}
