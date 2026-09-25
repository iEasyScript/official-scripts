package com.projectx.script.impl.combat.raksha

internal data class SequenceStep(
    val ability: String? = null,
    val attackBoss: Boolean = false,
    val retreat: Int? = null,
    val walkFromBossWhenNotTargeting: Int? = null,
    val delayWhenNotTargeting: Int = 0,
    val retreatWhenUnavailable: Int? = null,
    val waitTicks: Int = 1,
    val waitMillis: Int? = null,
)

internal sealed interface Response {
    data class Instant(val ability: String) : Response
    data class Sequence(val steps: List<SequenceStep>) : Response
    data class Sustained(val durationTicks: Int, val behaviour: Behaviour) : Response
}

internal enum class Behaviour { DODGE_BOMBS, BURN_DOME }

internal data class MechanicDef(
    val name: String,
    val priority: Int,
    val response: Response,
    val exclusive: Boolean = false,
    val retriggerAfter: Int = 5,
    val escapeSweepWhenNotTargeting: Int? = null,
    val sweepMover: String? = null,
)

internal object MechanicDefinitions {
    const val TAIL_SWEEP_CLEARANCE = 7
    const val TAIL_SWEEP_CLEARANCE_P4 = 9
    const val TAIL_SWEEP_FALLBACK_WALK = 5
    const val BOMB_MOVE_EVERY_TICKS = 3

    private val attackBoss = SequenceStep(attackBoss = true)

    val general: Map<Int, MechanicDef> = mapOf(
        RakshaIds.ANIM_TAIL_SWEEP_ESCAPE to MechanicDef(
            name = "Tail Sweep",
            priority = 50,
            exclusive = true,
            escapeSweepWhenNotTargeting = TAIL_SWEEP_CLEARANCE,
            sweepMover = "Escape",
            response = Response.Sequence(
                listOf(
                    SequenceStep(
                        ability = "Escape",
                        walkFromBossWhenNotTargeting = TAIL_SWEEP_CLEARANCE,
                        delayWhenNotTargeting = 2,
                        waitTicks = 2,
                    ),
                    attackBoss,
                ),
            ),
        ),
        RakshaIds.ANIM_TAIL_SWEEP_FREEDOM to MechanicDef(
            name = "Tail Sweep (Anticipation + Surge)",
            priority = 50,
            exclusive = true,
            retriggerAfter = 5,
            escapeSweepWhenNotTargeting = TAIL_SWEEP_CLEARANCE,
            sweepMover = "Surge",
            response = Response.Sequence(
                listOf(
                    SequenceStep(ability = "Anticipation", waitMillis = 2000),
                    SequenceStep(ability = "Surge", walkFromBossWhenNotTargeting = TAIL_SWEEP_CLEARANCE, waitTicks = 2),
                    attackBoss,
                ),
            ),
        ),
        RakshaIds.ANIM_INSTAKILL_BIND to MechanicDef(
            name = "Insta-kill Bind (Freedom)",
            priority = 90,
            exclusive = true,
            retriggerAfter = 3,
            response = Response.Instant("Freedom"),
        ),
        RakshaIds.ANIM_BOMBS to MechanicDef(
            name = "Bombs",
            priority = 70,
            retriggerAfter = 3,
            response = Response.Sustained(20, Behaviour.DODGE_BOMBS),
        ),
        RakshaIds.ANIM_SHADOW_BOMBARDMENT to MechanicDef(
            name = "Shadow Bomb / Bombardment",
            priority = 90,
            exclusive = true,
            retriggerAfter = 10,
            response = Response.Sequence(
                listOf(
                    SequenceStep(ability = "Freedom", waitTicks = 4),
                    SequenceStep(ability = "Surge"),
                    attackBoss,
                ),
            ),
        ),
    )

    val phase4: Map<Int, MechanicDef> = mapOf(
        RakshaIds.ANIM_TAIL_SWEEP_FREEDOM to MechanicDef(
            name = "Tail Sweep (P4) - Escape",
            priority = 60,
            exclusive = true,
            retriggerAfter = 5,
            escapeSweepWhenNotTargeting = TAIL_SWEEP_CLEARANCE_P4,
            sweepMover = "Escape",
            response = Response.Sequence(
                listOf(
                    SequenceStep(
                        ability = "Escape",
                        walkFromBossWhenNotTargeting = TAIL_SWEEP_CLEARANCE_P4,
                        retreatWhenUnavailable = TAIL_SWEEP_FALLBACK_WALK,
                        waitTicks = 4,
                    ),
                    attackBoss,
                ),
            ),
        ),
        RakshaIds.ANIM_DOME to MechanicDef(
            name = "Shadow Detonation Dome - BURN",
            priority = 95,
            exclusive = true,
            retriggerAfter = 5,
            response = Response.Sustained(30, Behaviour.BURN_DOME),
        ),
        RakshaIds.ANIM_SHADOW_BOMBARDMENT to MechanicDef(
            name = "Shadow Bomb (P4) - Freedom + step back",
            priority = 90,
            exclusive = true,
            retriggerAfter = 10,
            response = Response.Sequence(
                listOf(
                    SequenceStep(ability = "Freedom", waitTicks = 4),
                    SequenceStep(retreat = TAIL_SWEEP_FALLBACK_WALK, waitTicks = 2),
                    attackBoss,
                ),
            ),
        ),
    )

    fun forAnimation(anim: Int, phase: Int): MechanicDef? =
        (if (phase >= 4) phase4[anim] else null) ?: general[anim]
}
