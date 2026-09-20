package com.projectx.script.impl.archaeology.aio

import org.projectx.core.game.skill.Skill
import world.gregs.voidps.cache.Cache
import world.gregs.voidps.type.Tile
import com.projectx.game.items.Item
import com.projectx.game.nxt.entity.location.SceneObject
import com.projectx.game.nxt.entity.npc.NPC
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigVisibilityProvider
import com.projectx.script.ConfigurableScript
import com.projectx.script.EnumConfigItem
import com.projectx.script.IntConfigItem
import com.projectx.script.ScriptCategory
import com.projectx.script.ScriptDescription
import com.projectx.script.State
import com.projectx.script.StateMachineScript
import com.projectx.script.api.*
import com.projectx.script.event.Event
import com.projectx.script.event.impl.ManualDoAction
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.ui.backend.dsl.scopes.xpProgressBar
import com.projectx.util.formatElapsedTime
import com.projectx.util.getFormattedXpPerHour

private const val EXCAVATE = "Excavate"

/** A hotspot the account has never dug offers this instead, under the dig site's soil name. */
private const val UNCOVER = "Uncover"
private const val SOIL_BOX = "Archaeological soil box"
private const val COMPLETE_TOME = 49976

/** Cache categories every Archaeology material and soil shares, so new dig sites need no id list here. */
private const val MATERIAL_CATEGORY = 4602
private const val SOIL_CATEGORY = 4603

private const val STORAGE_CONTAINER = "Material storage container"

private const val DEPOSIT_MATERIALS = "Deposit materials"
private const val STORE = "Store"
private const val DEPOSIT_ALL = "Deposit all"
private const val LOAD_PRESET = "Load Last Preset from"
private const val BANK = "Bank"

/** Options that make something a place to put materials, in the order the click-to-pick fallback prefers them. */
private val DEPOSIT_OPTIONS = listOf(DEPOSIT_MATERIALS, STORE, DEPOSIT_ALL, LOAD_PRESET, BANK)

private val Item.isMaterial get() = getDef().category == MATERIAL_CATEGORY
private val Item.isSoil get() = getDef().category == SOIL_CATEGORY

/** What to do with the soil a hotspot yields alongside its materials. */
enum class SoilHandling(private val label: String) {
    FILL_BOX_THEN_DROP("Fill soil box, drop the rest"),
    DROP("Drop it"),
    KEEP("Keep it"),
    ;

    override fun toString() = label
}

/** Where the materials go once the backpack fills up. */
enum class MaterialHandling(private val label: String) {
    AUTO("Auto - cart, container, then bank"),
    MATERIALS_CART_ONLY("Materials cart"),
    STORAGE_CONTAINER_ONLY("Material storage container"),
    BANK_PRESET("Bank"),
    DROP("Drop them"),
    KEEP("Keep them"),
    ;

    override fun toString() = label
}

/** Somewhere to deposit that the user picked by clicking it, which outranks whatever the settings say. */
private data class ClickedDeposit(val option: String, val tile: Tile, val isNpc: Boolean)

@ScriptDescription(
    name = "AIO Archaeology",
    version = "2.3.3",
    author = "Cryptic",
    description = "Excavates any hotspot in the game. Pick a dig site and hotspot in the settings, or click one in game.",
    category = ScriptCategory.ARCHAEOLOGY,
)
class AioArchaeology : StateMachineScript<AioArchaeology>(), ConfigurableScript, ConfigVisibilityProvider {

    private val excavationSection = ConfigSection("Excavation", "Which hotspot to dig, and how far to look for it.")

    private val digSite = EnumConfigItem(
        name = "Dig site",
        description = "Narrows the hotspot list below to one dig site. Get yourself to the site first - the script digs there, it does not travel.",
        enumValues = DigSite.entries.toTypedArray(),
        initialValue = DigSite.KHARID_ET,
    )

    private val kharidEtHotspot = hotspotConfig(DigSite.KHARID_ET)
    private val infernalSourceHotspot = hotspotConfig(DigSite.INFERNAL_SOURCE)
    private val everlightHotspot = hotspotConfig(DigSite.EVERLIGHT)
    private val senntistenHotspot = hotspotConfig(DigSite.SENNTISTEN)
    private val moonriseHotspot = hotspotConfig(DigSite.MOONRISE)
    private val orthenHotspot = hotspotConfig(DigSite.ORTHEN)
    private val stormguardHotspot = hotspotConfig(DigSite.STORMGUARD_CITADEL)
    private val warforgeHotspot = hotspotConfig(DigSite.WARFORGE)
    private val daemonheimHotspot = hotspotConfig(DigSite.DAEMONHEIM)
    private val otherHotspot = hotspotConfig(DigSite.OTHER)

    private val searchRange = IntConfigItem(
        name = "Search range",
        description = "How far, in tiles, to look for the hotspot before walking back to where it was last seen.",
        initialValue = 24,
        min = 5,
        max = 64,
    )

    private val soilSection = ConfigSection("Soil")

    private val soilHandling = EnumConfigItem(
        name = "Soil",
        description = "Soil is worth keeping only if you screen it. Filling the soil box first keeps what fits and drops the overflow.",
        enumValues = SoilHandling.entries.toTypedArray(),
        initialValue = SoilHandling.FILL_BOX_THEN_DROP,
    )

    private val materialSection = ConfigSection("Materials")

    private val materialHandling = EnumConfigItem(
        name = "Materials",
        description = "Auto uses a Materials cart if one is in range, then a Material storage container, then a bank, and drops them if none of those is nearby.",
        enumValues = MaterialHandling.entries.toTypedArray(),
        initialValue = MaterialHandling.AUTO,
    )

    private val usePorters = BooleanConfigItem(
        name = "Use porters",
        description = "Charge a sign of the porter from the backpack when one is carried, sending materials straight to material storage.",
        initialValue = true,
    )

    private val depositRange = IntConfigItem(
        name = "Deposit range",
        description = "How far, in tiles, to look for a cart, container or bank when the backpack fills up.",
        initialValue = 30,
        min = 5,
        max = 64,
    )

    private val bankPreset = IntConfigItem(
        name = "Bank preset",
        description = "The preset to load once the bank is open. Ignored while carrying a complete tome, which is banked instead.",
        initialValue = 1,
        min = 1,
        max = 9,
    )

    private val hotspotConfigs = mapOf(
        DigSite.KHARID_ET to kharidEtHotspot,
        DigSite.INFERNAL_SOURCE to infernalSourceHotspot,
        DigSite.EVERLIGHT to everlightHotspot,
        DigSite.SENNTISTEN to senntistenHotspot,
        DigSite.MOONRISE to moonriseHotspot,
        DigSite.ORTHEN to orthenHotspot,
        DigSite.STORMGUARD_CITADEL to stormguardHotspot,
        DigSite.WARFORGE to warforgeHotspot,
        DigSite.DAEMONHEIM to daemonheimHotspot,
        DigSite.OTHER to otherHotspot,
    )

    /** Set by clicking a hotspot in game, which retargets the script without touching the settings. */
    private var clickedHotspot: String? = null
    private var clickedDeposit: ClickedDeposit? = null

    /** Where the hotspot was last found, so the script can walk back after a bank trip. */
    var hotspotTile: Tile = Tile.EMPTY
    var status: String = "Starting"

    private var startTime = 0L
    private var startingXp = 0

    val selectedHotspot: ExcavationHotspot get() = hotspotConfigs.getValue(digSite.value).value
    val hotspotName: String get() = clickedHotspot ?: selectedHotspot.objectName
    val soilMode: SoilHandling get() = soilHandling.value
    val materialMode: MaterialHandling get() = materialHandling.value
    val portersEnabled: Boolean get() = usePorters.value
    val hotspotSearchRange: Int get() = searchRange.value
    val depositSearchRange: Int get() = depositRange.value
    val preset: Int get() = bankPreset.value

    val hasSoil get() = inventory.any { it.isSoil }
    val hasMaterials get() = inventory.any { it.isMaterial }
    val hasSoilBox get() = inventory.hasItem(SOIL_BOX)

    override fun onStart() {
        startTime = System.currentTimeMillis()
        startingXp = getXp(Skill.ARCHAEOLOGY)
        // States are singletons that outlive a run, so their latched flags would carry into the next one.
        Gather.reset()
        Soil.reset()
    }

    /**
     * Hotspot and deposit clicks are meaningful in every state, not just the one that happens to be current, so they
     * are handled here rather than in a state's own handler.
     */
    override fun onEvent(event: Event) {
        if (event is ManualDoAction) rememberClickedTarget(event.target)
        super.onEvent(event)
    }

    /** A settings change means the remembered position belongs to the old hotspot, so drop it. */
    fun onConfigUpdated() {
        clickedHotspot = null
        hotspotTile = Tile.EMPTY
    }

    override fun isConfigItemVisible(fieldName: String, item: ConfigItem<*>): Boolean {
        hotspotConfigs.forEach { (site, config) -> if (config === item) return site == digSite.value }
        if (item === bankPreset) return materialMode == MaterialHandling.BANK_PRESET || materialMode == MaterialHandling.AUTO
        return true
    }

    private fun rememberClickedTarget(target: Any?) {
        when (target) {
            is SceneObject -> {
                if (target.hasOption(EXCAVATE)) {
                    clickedHotspot = target.name()
                    hotspotTile = target.tile
                } else {
                    DEPOSIT_OPTIONS.firstOrNull { target.hasOption(it) }
                        ?.let { clickedDeposit = ClickedDeposit(it, target.tile, isNpc = false) }
                }
            }

            is NPC -> DEPOSIT_OPTIONS.firstOrNull { target.hasOption(it) }
                ?.let { clickedDeposit = ClickedDeposit(it, target.tile, isNpc = true) }
        }
    }

    /**
     * Interacts with whatever the user last clicked as a deposit point, walking to it if it is out of range.
     * Returns false once standing there has not turned it up, so the configured handling gets its turn.
     */
    suspend fun useClickedDeposit(): Boolean {
        val clicked = clickedDeposit ?: return false
        val interacted = if (clicked.isNpc)
            interactClosestNPC(clicked.option, depositSearchRange)
        else
            interactClosestReachableObject(clicked.option, depositSearchRange)
        if (interacted) {
            status = "Depositing where you clicked"
            waitThenDelayUntil(1200, 60000) { !hasMaterials || bankOpen }
            return true
        }
        if (clicked.tile == Tile.EMPTY || clicked.tile.withinDistance(localPlayer.tile, 11)) return false
        status = "Walking to where you clicked"
        if (walkTo(clicked.tile.randomize(3), true))
            delayUntil(5283) { clicked.tile.withinDistance(localPlayer.tile, 11) }
        delay(150, 200)
        return true
    }

    override fun render() {
        ImGuiDsl.window("AIO Archaeology") {
            text("Runtime: ${formatElapsedTime(System.currentTimeMillis(), startTime)}")
            text("Hotspot: $hotspotName")
            if (clickedHotspot == null) text("Site: ${selectedHotspot.digSite}${siteArea()}")
            separator()
            text("Status: $status")
            text("XP/hr: ${getFormattedXpPerHour(startingXp, getXp(Skill.ARCHAEOLOGY), startTime)}")
            xpProgressBar(Skill.ARCHAEOLOGY)
        }
    }

    private fun siteArea() = selectedHotspot.area.let { if (it.isEmpty()) "" else " - $it" }

    override fun getStartState() = Gather

    private fun hotspotConfig(site: DigSite): EnumConfigItem<ExcavationHotspot> {
        val choices = ExcavationHotspot.inSite(site)
        return EnumConfigItem(
            name = "Hotspot",
            description = "The hotspot to excavate at $site. The number beside each is the Archaeology level it needs.",
            enumValues = choices,
            initialValue = choices.first(),
        )
    }
}

object Gather : State<AioArchaeology>() {
    private var lastTimeSprite: Tile? = null

    fun reset() {
        lastTimeSprite = null
    }

    override suspend fun AioArchaeology.checkNext() = when {
        !inventory.isFull -> null
        hasSoil && soilMode != SoilHandling.KEEP -> Soil
        hasMaterials && materialMode != MaterialHandling.KEEP -> Deposit
        else -> null
    }

    override suspend fun AioArchaeology.stateLoop() {
        if (captureSerenSpirit()) return
        if (portersEnabled) checkPorter()

        if (inventory.isFull) {
            status = "Backpack full - nothing set to clear it"
            delay(2400, 600)
            return
        }

        val sprite = timeSpriteTile(hotspotSearchRange)
        if (localPlayer.isAniMoving && lastTimeSprite == sprite) return

        val hotspot = findClosestReachableObjectToTile(sprite ?: localPlayer.tile, hotspotSearchRange) {
            it.name() == hotspotName && it.hasOption(EXCAVATE)
        }
        if (hotspot != null) {
            hotspotTile = hotspot.tile
            if (hotspot.interact(EXCAVATE)) {
                status = if (sprite == null) "Excavating" else "Excavating a time sprite"
                lastTimeSprite = sprite
                delay(3500, 5200)
            }
        } else if (uncoverBuriedHotspot()) {
            return
        } else if (hotspotTile != Tile.EMPTY) {
            status = "Walking back to the hotspot"
            if (hotspotTile.withinDistance(localPlayer.tile, 10) || walkTo(hotspotTile.randomize(3), true))
                delayUntil(5283) { hotspotTile.withinDistance(localPlayer.tile, 11) }
        } else {
            status = "No '$hotspotName' in range - click one, or pick another in the settings"
        }
        delay(150, 200)
    }

    /**
     * Uncovers a hotspot that has never been dug, which is why the named one is not there to find.
     *
     * A hotspot the account has not uncovered does not carry its own name or an Excavate option yet. The placed
     * object is an unnamed shell that a varbit transforms: until it is uncovered it shows as the dig site's soil
     * - "Aerated sediment", "Ancient gravel" - offering Uncover, and only afterwards as the named debris with
     * Excavate.
     *
     * The shell still lists what it can become, so the wanted hotspot can be told apart from its neighbours by
     * asking whether any of its transforms is named the one we are after. Failing that any buried spot will do:
     * uncovering pays a one-off experience reward and reveals a hotspot either way.
     *
     * Returns true when it uncovered something, so the pass ends there and the next one looks again.
     */
    private suspend fun AioArchaeology.uncoverBuriedHotspot(): Boolean {
        val wanted = findClosestReachableObject(hotspotSearchRange) { obj ->
            obj.hasOption(UNCOVER) && obj.defs.transforms?.any { Cache.loc(it)?.name == hotspotName } == true
        }
        val buried = wanted
            ?: findClosestReachableObject(hotspotSearchRange) { it.hasOption(UNCOVER) }
            ?: return false
        status = "Uncovering ${buried.name()}"
        if (!buried.interact(UNCOVER)) return false
        // The object is replaced by the named hotspot, so waiting on it disappearing is waiting on the uncover.
        waitThenDelayUntil(1200, 12_000) { !buried.exists && !localPlayer.isAnimating }
        delay(600, 300)
        return true
    }
}

object Soil : State<AioArchaeology>() {
    /** Latched when a Fill moved nothing; only screening the box can undo that, which this script never does. */
    private var soilBoxFull = false

    fun reset() {
        soilBoxFull = false
    }

    override suspend fun AioArchaeology.checkNext() = if (!hasSoil || soilMode == SoilHandling.KEEP) Gather else null

    override suspend fun AioArchaeology.stateLoop() {
        // A full soil box moves nothing, so stop clicking Fill and drop from here on rather than time out every trip.
        if (soilMode == SoilHandling.FILL_BOX_THEN_DROP && hasSoilBox && !soilBoxFull) {
            status = "Filling the soil box"
            val before = inventory.count { it.isSoil }
            if (inventory.clickItem(SOIL_BOX, "Fill"))
                waitThenDelayUntil(1200, 4000) { inventory.count { it.isSoil } < before }
            soilBoxFull = inventory.count { it.isSoil } >= before
            if (!hasSoil) return
        }
        status = "Dropping soil"
        inventory.filter { it.isSoil }.forEach {
            it.click("Drop")
            delay(110, 100)
        }
        delay(1200, 1100)
    }
}

object Deposit : State<AioArchaeology>() {
    override suspend fun AioArchaeology.checkNext() =
        if (!hasMaterials || materialMode == MaterialHandling.KEEP) Gather else null

    override suspend fun AioArchaeology.stateLoop() {
        if (bankOpen) {
            status = "Banking"
            // A complete tome is worth keeping, and a preset would leave it behind.
            if (inventory.hasItem(COMPLETE_TOME)) depositBankInventory() else loadBankPreset(preset)
            waitThenDelayUntil(1200, 10000) { !bankOpen || !hasMaterials }
            return
        }

        if (useClickedDeposit()) return

        val handled = when (materialMode) {
            MaterialHandling.MATERIALS_CART_ONLY -> useCart()
            MaterialHandling.STORAGE_CONTAINER_ONLY -> useStorageContainer()
            MaterialHandling.BANK_PRESET -> useBank()
            MaterialHandling.DROP -> dropMaterials()
            MaterialHandling.KEEP -> true
            MaterialHandling.AUTO -> useCart() || useStorageContainer() || useBank() || dropMaterials()
        }
        if (!handled)
            status = "Nothing in range to take the materials"
        delay(150, 200)
    }

    /**
     * The cart takes materials only, so a complete tome needs the wider 'Deposit all' when one is offered.
     *
     * Matched on the option rather than the name: 'Deposit materials' belongs to Archaeology carts alone, and the
     * carts ship under several ids and both spellings of the name.
     */
    private suspend fun AioArchaeology.useCart(): Boolean {
        val wantsDepositAll = inventory.hasItem(COMPLETE_TOME) &&
            findClosestObject(range = depositSearchRange) { it.hasOption(DEPOSIT_ALL) } != null
        val option = if (wantsDepositAll) DEPOSIT_ALL else DEPOSIT_MATERIALS
        val cart = findClosestReachableObject(depositSearchRange) { it.hasOption(option) } ?: return false
        status = "Depositing at the ${cart.name().lowercase()}"
        return interactAndWait(cart, option)
    }

    private suspend fun AioArchaeology.useStorageContainer(): Boolean {
        val container = findClosestReachableObject(depositSearchRange) {
            it.name() == STORAGE_CONTAINER && it.hasOption(STORE)
        } ?: return false
        status = "Storing at the material storage container"
        return interactAndWait(container, STORE)
    }

    private suspend fun AioArchaeology.useBank(): Boolean {
        val bank = findClosestReachableObject(depositSearchRange) {
            it.hasOption(LOAD_PRESET) || it.hasOption(BANK)
        } ?: return false
        val option = if (bank.hasOption(LOAD_PRESET)) LOAD_PRESET else BANK
        status = "Heading to the bank"
        return interactAndWait(bank, option)
    }

    private suspend fun AioArchaeology.dropMaterials(): Boolean {
        status = "Dropping materials"
        inventory.filter { it.isMaterial }.forEach {
            it.click("Drop")
            delay(110, 100)
        }
        delay(1200, 1100)
        return true
    }

    private suspend fun AioArchaeology.interactAndWait(target: SceneObject, option: String): Boolean {
        if (!target.interact(option)) return false
        waitThenDelayUntil(1200, 60000) { !hasMaterials || bankOpen }
        return true
    }
}
