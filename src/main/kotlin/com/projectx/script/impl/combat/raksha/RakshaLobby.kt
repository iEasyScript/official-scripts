package com.projectx.script.impl.combat.raksha

import com.projectx.game.input.Key
import com.projectx.game.interfaces.IFSlot
import com.projectx.game.interfaces.InstanceSystem
import com.projectx.script.Script
import com.projectx.script.api.ServerTick
import com.projectx.script.api.WarsRetreat
import com.projectx.script.api.abilityReady
import com.projectx.script.api.awaitServerTick
import com.projectx.script.api.castAbility
import com.projectx.script.api.findClosestObject
import com.projectx.script.api.healthCurrent
import com.projectx.script.api.inInstancedArea
import com.projectx.script.api.instanceTimeRemainingMs
import com.projectx.script.api.interfaces
import com.projectx.script.api.inventory
import com.projectx.script.api.isDialogOpen
import com.projectx.script.api.pressKey
import com.projectx.script.api.typeText
import com.projectx.util.gaussian

internal class RakshaLobby(
    var settings: RakshaSettings,
    private val fight: RakshaFight,
    private val log: (String) -> Unit,
) {
    var status = ""
        private set

    private var enteredTick: Long? = null
    private var lifeTransferDone = false
    private var foodEaten = false
    private var rejoinAttempts = 0
    private var attemptStartedAt = 0L
    private var failedAttempts = 0
    private var createdAt = 0L
    private var startConfirmed = false
    private var instanceSizeSet = false

    val isHere: Boolean
        get() = !WarsRetreat.isHere && !inInstancedArea && findClosestObject(RakshaIds.SECURITY_GATE, 30) != null

    fun leftLobby() {
        enteredTick = null
        lifeTransferDone = false
        foodEaten = false
    }

    fun resetTrip() {
        leftLobby()
        rejoinAttempts = 0
        attemptStartedAt = 0L
        failedAttempts = 0
        startConfirmed = false
        instanceSizeSet = false
    }

    suspend fun pass(script: Script) {
        val arrived = enteredTick ?: ServerTick.count.also { enteredTick = it }
        if (fight.conjuring()) {
            status = "Conjuring"
            script.delayUntil(gaussian(3600L, 700L)) { !fight.conjuring() }
            return
        }
        if (fight.canSummonConjures()) {
            status = "Conjuring the undead army"
            if (fight.summonConjures()) {
                log("Conjuring Undead Army in the lobby")
                script.delayUntil(gaussian(4200L, 900L)) { fight.conjuresUp() || fight.conjuring() }
            }
            return
        }
        if (!lifeTransferDone && fight.conjuresUp() && abilityReady(LIFE_TRANSFER)) {
            status = "Life Transfer"
            val before = healthCurrent
            if (castAbility(LIFE_TRANSFER)) {
                lifeTransferDone = true
                log("Life Transfer cast into the conjures")
                script.delayUntil(gaussian(2400L, 600L)) { healthCurrent < before }
                script.delay(380, 140)
            }
            return
        }
        if (lifeTransferDone && !foodEaten) {
            val food = inventory.firstOrNull { item -> item.amount > 0 && item.invOps.any { it == "Eat" } }
            if (food != null) {
                status = "Eating before entry"
                val before = inventory.count(food.id)
                if (food.click("Eat")) {
                    foodEaten = true
                    log("Ate one ${food.name} after Life Transfer")
                    script.delayUntil(gaussian(1800L, 500L)) { inventory.count(food.id) < before }
                    script.delay(360, 140)
                }
                return
            }
        }
        val prepared = fight.conjuresUp() && lifeTransferDone && foodEaten
        if (!prepared && ServerTick.count - arrived <= LOBBY_PREP_WAIT_TICKS) {
            status = "Preparing in the lobby"
            script.awaitServerTick()
            return
        }
        handleInstance(script)
    }

    private suspend fun handleInstance(script: Script) {
        if (attemptStartedAt > 0 && System.currentTimeMillis() - attemptStartedAt > INSTANCE_TIMEOUT_MS) {
            failedAttempts++
            attemptStartedAt = 0L
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                log("Instance creation kept failing - returning to War's Retreat")
                failedAttempts = 0
                if (WarsRetreat.teleport()) script.delayUntil(gaussian(9000L, 1800L)) { WarsRetreat.isHere }
                return
            }
        }
        if (settings.isJoiner) return joinLeader(script)
        if (InstanceSystem.isOpen() || isDialogOpen()) return startNewInstance(script)
        if (createdAt > 0) {
            if (System.currentTimeMillis() - createdAt < INSTANCE_CREATE_GRACE_MS) {
                status = "Waiting for the new instance"
                script.awaitServerTick()
                return
            }
            createdAt = 0L
        }
        val minutesLeft = instanceTimeRemainingMs / 60_000
        if (minutesLeft < 1 || rejoinAttempts > MAX_REJOIN_ATTEMPTS) {
            if (minutesLeft > 0) log("Instance has only $minutesLeft min left - creating a fresh one")
            startNewInstance(script)
        } else {
            rejoin(script)
        }
    }

    private suspend fun rejoin(script: Script) {
        val gate = findClosestObject(RakshaIds.SECURITY_GATE, 30) ?: return
        status = "Rejoining the instance"
        rejoinAttempts++
        if (gate.interact("Rejoin last instance"))
            script.delayUntil(gaussian(9000L, 1600L)) { inInstancedArea || isDialogOpen() }
    }

    private suspend fun startNewInstance(script: Script) {
        if (attemptStartedAt == 0L) attemptStartedAt = System.currentTimeMillis()

        if (InstanceSystem.isOpen()) {
            if (settings.inParty && settings.isPartyLeader && !instanceSizeSet) {
                instanceSizeSet = true
                status = "Setting instance size to ${settings.partySize}"
                log("Duo: setting instance size to ${settings.partySize} players")
                repeat(settings.partySize) {
                    val size = InstanceSystem.instanceDetails?.maxPlayers ?: return@repeat
                    if (size >= settings.partySize) return@repeat
                    InstanceSystem.setMaxPlayers(true)
                    script.delayUntil(gaussian(1800L, 500L)) { (InstanceSystem.instanceDetails?.maxPlayers ?: size) != size }
                    script.delay(320, 120)
                }
                return
            }
            status = "Starting a new instance"
            if (InstanceSystem.startInstance()) {
                log("Instance settings window confirmed - creating instance")
                attemptStartedAt = 0L
                failedAttempts = 0
                rejoinAttempts = 0
                instanceSizeSet = false
                startConfirmed = true
                createdAt = System.currentTimeMillis()
                script.delayUntil(gaussian(7000L, 1400L)) { inInstancedArea || isDialogOpen() || !InstanceSystem.isOpen() }
            }
            return
        }

        if (isDialogOpen()) {
            if (startConfirmed) {
                startConfirmed = false
                createdAt = 0L
                log("Dialogue after Start - instance is still live, rejoining")
                script.pressKey(Key.ESCAPE)
                script.delayUntil(gaussian(1800L, 400L)) { !isDialogOpen() }
                rejoin(script)
                return
            }
            status = "Answering the instance dialogue"
            if (IFSlot(DIALOGUE, DIALOGUE_SECOND_OPTION).dialogueContinue())
                script.delayUntil(gaussian(3000L, 700L)) { InstanceSystem.isOpen() || !isDialogOpen() }
            return
        }

        val gate = findClosestObject(RakshaIds.SECURITY_GATE, 30) ?: return
        status = "Opening the Security gate"
        if (gate.interact("Enter"))
            script.delayUntil(gaussian(7000L, 1400L)) { InstanceSystem.isOpen() || isDialogOpen() || inInstancedArea }
    }

    private suspend fun joinLeader(script: Script) {
        val leader = settings.partyLeader.trim()
        if (leader.isEmpty()) {
            status = "No party owner name set"
            log("Duo: no party owner name configured - cannot join")
            script.delay(gaussian(2400, 600))
            return
        }
        if (attemptStartedAt == 0L) attemptStartedAt = System.currentTimeMillis()
        if (!InstanceSystem.isOpen()) {
            val gate = findClosestObject(RakshaIds.SECURITY_GATE, 30) ?: return
            status = "Opening the Security gate"
            if (gate.interact("Enter")) script.delayUntil(gaussian(7000L, 1400L)) { InstanceSystem.isOpen() || inInstancedArea }
            return
        }
        status = "Joining $leader's instance"
        log("Duo: joining $leader's instance")
        with(InstanceSystem) { script.joinInstance() }
        script.delayUntil(gaussian(4200L, 900L)) { inInstancedArea || !interfaces.isOpen(NAME_PROMPT) }
        if (inInstancedArea || !interfaces.isOpen(NAME_PROMPT)) return
        script.typeText(leader)
        script.pressKey(Key.RETURN)
        script.delayUntil(gaussian(6000L, 1200L)) { inInstancedArea }
    }

    private companion object {
        const val LIFE_TRANSFER = "Life Transfer"
        const val LOBBY_PREP_WAIT_TICKS = 22
        const val INSTANCE_TIMEOUT_MS = 30_000L
        const val INSTANCE_CREATE_GRACE_MS = 12_000L
        const val MAX_FAILED_ATTEMPTS = 3
        const val MAX_REJOIN_ATTEMPTS = 3
        const val DIALOGUE = 1188
        const val DIALOGUE_SECOND_OPTION = 13
        const val NAME_PROMPT = 1469
    }
}
