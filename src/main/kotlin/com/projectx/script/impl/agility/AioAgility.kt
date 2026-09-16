package com.projectx.script.impl.agility

import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.api.SkillTracker
import com.projectx.script.api.captureSerenSpirit
import com.projectx.script.api.findClosestReachableNPC
import com.projectx.script.api.findClosestReachableObject
import com.projectx.script.api.isLoggedIn
import com.projectx.script.api.isPlayerBusy
import com.projectx.script.api.localPlayer
import com.projectx.script.api.walkTo
import com.projectx.script.event.Event
import com.projectx.script.event.impl.Chat
import org.projectx.core.game.skill.Skill
import world.gregs.voidps.type.Tile
import kotlin.math.abs
import kotlin.math.max

@ScriptDescription(
    name = "AIO Agility",
    version = "1.1.0",
    author = "Cryptic",
    description = "Runs laps of an agility course until stopped: Wilderness, Hefin in Prifddinas or Anachronia. " +
        "Start anywhere on the course.",
    category = ScriptCategory.AGILITY,
)
class AioAgility : Script(), ConfigurableScript {

    enum class CourseChoice(private val label: String, val course: AgilityCourse?) {
        AUTOMATIC("Automatic", null),
        WILDERNESS("Wilderness", AgilityCourse.WILDERNESS),
        HEFIN("Hefin (Prifddinas)", AgilityCourse.HEFIN),
        ANACHRONIA("Anachronia", AgilityCourse.ANACHRONIA);

        override fun toString() = label
    }

    private val courseChoice = EnumConfigItem(
        name = "Course",
        description = "The course to run. Automatic picks the one you are standing at.",
        enumValues = CourseChoice.entries.toTypedArray(),
        initialValue = CourseChoice.AUTOMATIC,
    )

    private val tracker = SkillTracker(Skill.AGILITY)

    /** The step after the last obstacle taken; obstacles that can be crossed both ways must never be retaken. */
    private var expected: Step? = null

    override suspend fun loop() {
        if (!isLoggedIn()) return delay(1800, 600)
        if (!isPlayerBusy() && captureSerenSpirit()) return tracker.add("Seren spirits")

        val here = localPlayer.tile
        val course = courseChoice.value.course
            ?: AgilityCourse.entries.firstOrNull { it.stepAt(here) != null || it.isNear(here) }
        if (course == null) {
            println("[AioAgility] Not at a supported agility course ($here); stopping")
            return stop()
        }
        if (expected != null && expected !in course.steps) expected = null

        val step = expected?.takeIf { it.takenFrom.contains(here) } ?: course.stepAt(here)?.let { found ->
            // Standing where the obstacle just crossed was taken from, e.g. after walking towards the next one.
            expected?.takeIf { course.stepAfter(found) == it } ?: found
        }
        when {
            step != null -> take(course, step)
            isPlayerBusy() -> waitUntilIdle(maxTicks = 15, idleChecks = 2)
            here.y > UNDERGROUND_Y -> climbOutOfPit()
            !waitUntilIdle(maxTicks = 10, idleChecks = RECOVER_IDLE_TICKS) -> Unit
            course.stepAt(localPlayer.tile) != null -> Unit
            else -> expected?.let { take(course, it) } ?: resumeFromNearestObstacle(course)
        }
    }

    private suspend fun take(course: AgilityCourse, step: Step) {
        expected = step
        val obstacle = step.shortcut?.takeIf { it.interact() } ?: step.obstacle.takeIf { it.interact() }
        if (obstacle == null) return approach(step.obstacle)

        awaitLanding(course, step)
        val landed = course.stepAt(localPlayer.tile)
        if (landed != null && landed != step) {
            expected = course.stepAfter(step)
            if (step == course.steps.last() && landed == course.steps.first()) {
                tracker.add("Laps")
                println("[AioAgility] ${course.label} lap ${tracker.countOf("Laps")} complete")
            }
        }
        delay(350, 200)
    }

    /**
     * Walks part of the way to an obstacle the scene has not loaded: the client only lists a map square's objects
     * some time after the player reaches it.
     */
    private suspend fun approach(obstacle: Obstacle) {
        val target = obstacle.tile
        val here = localPlayer.tile
        if (target == null || target.plane != here.plane || distance(here, target) <= 1) {
            println("[AioAgility] ${obstacle.name} not found from $here")
            return delay(1200, 400)
        }
        val dx = (target.x - here.x).coerceIn(-APPROACH_TILES, APPROACH_TILES)
        val dy = (target.y - here.y).coerceIn(-APPROACH_TILES, APPROACH_TILES)
        walkTo(Tile.of(here.x + dx, here.y + dy, here.plane), false)
        waitUntilStoppedMoving(maxTicks = 20, stillChecks = 2)
    }

    /**
     * Waits until the player stands still somewhere the next obstacle is taken from. Some obstacles pause without
     * animating part-way, so a player only counts as stopped short once idle for [IDLE_GRACE_MILLIS].
     */
    private suspend fun awaitLanding(course: AgilityCourse, step: Step) {
        var lastBusy = System.currentTimeMillis()
        delayUntil(OBSTACLE_TIMEOUT_MILLIS) {
            val now = System.currentTimeMillis()
            if (isPlayerBusy()) {
                lastBusy = now
                false
            } else {
                val landed = course.stepAt(localPlayer.tile)
                (landed != null && landed != step) || now - lastBusy > IDLE_GRACE_MILLIS
            }
        }
    }

    private suspend fun climbOutOfPit() {
        val ladder = findClosestReachableObject(30) { it.name() == "Ladder" && it.hasOption("Climb-up") }
        if (ladder == null || !ladder.interact("Climb-up")) {
            println("[AioAgility] In a pit at ${localPlayer.tile} but no ladder to climb")
            return delay(1800, 600)
        }
        delayUntil(15000) { localPlayer.tile.y < UNDERGROUND_Y && !isPlayerBusy() }
        delay(600, 300)
    }

    /** Lost with nothing expected, e.g. started off every known spot: carry on from the obstacle the player can reach. */
    private suspend fun resumeFromNearestObstacle(course: AgilityCourse) {
        val objects = course.steps.flatMap { listOfNotNull(it.obstacle, it.shortcut) }.filterIsInstance<Obstacle.Object>()
        val npcs = course.steps.map { it.obstacle }.filterIsInstance<Obstacle.Npc>()

        val target: Obstacle? = findClosestReachableObject(30) { obj -> objects.any { it.matches(obj) } }
            ?.let { obj -> objects.first { it.matches(obj) } }
            ?: findClosestReachableNPC(30) { npc -> npcs.any { it.matches(npc) } }
                ?.let { npc -> npcs.first { it.matches(npc) } }
        val step = course.steps.firstOrNull { it.obstacle == target || it.shortcut == target }

        if (step == null) {
            println("[AioAgility] Lost on the ${course.label} course at ${localPlayer.tile}; stopping")
            return stop()
        }
        take(course, step)
    }

    private fun distance(a: Tile, b: Tile) = max(abs(a.x - b.x), abs(a.y - b.y))

    override fun onEvent(event: Event) {
        if (event is Chat && FALL_MESSAGES.any { event.message.contains(it, ignoreCase = true) }) {
            println("[AioAgility] Failed an obstacle: ${event.message}")
        }
    }

    override fun render() = tracker.window("AIO Agility")

    override fun onStop() = println("[AioAgility] Stopped after ${tracker.countOf("Laps")} laps")

    private companion object {
        const val UNDERGROUND_Y = 6400
        const val OBSTACLE_TIMEOUT_MILLIS = 25_000L
        const val IDLE_GRACE_MILLIS = 5_000L
        const val RECOVER_IDLE_TICKS = 5
        const val APPROACH_TILES = 12
        val FALL_MESSAGES = listOf("slip and fall", "lose your footing")
    }
}
