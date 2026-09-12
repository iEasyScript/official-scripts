package com.projectx.script.impl.easy

import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.api.*

private const val TARGET = "Master Farmer"

@ScriptDescription(
    name = "Master Farmer",
    version = "1.0.0",
    author = "Easy",
    description = "Pickpockets master farmer in Draynor"
)
class MasterFarmer : Script() {
    override suspend fun loop() {
        if (healthCurrent <= 50 || inventory.isFull) {
            if (interactClosestReachableObject("Counter", "Load Last Preset from"))
                waitThenDelayUntil(1200, 15000) { !inventory.isFull && healthPercent > 70.0 }
            return
        }
        if (interactClosestNPC(TARGET, "Pickpocket"))
            delay(532, 1259)
    }
}