package com.projectx.script.impl.geflipper

import com.projectx.game.input.Key
import com.projectx.script.BooleanConfigItem
import com.projectx.script.ConfigSection
import com.projectx.script.ConfigurableScript
import com.projectx.script.IntConfigItem
import com.projectx.script.Script
import com.projectx.script.ScriptDescription
import com.projectx.script.StringConfigItem
import com.projectx.script.api.GrandExchange
import com.projectx.script.api.GrandExchangeOffer
import com.projectx.script.api.GrandExchangePrices
import com.projectx.script.api.awaitGrandExchangePrices
import com.projectx.script.api.coinPouch
import com.projectx.script.api.geAbort
import com.projectx.script.api.geBuy
import com.projectx.script.api.geCollectAll
import com.projectx.script.api.geOpen
import com.projectx.script.api.geSell
import com.projectx.script.api.inventory
import com.projectx.script.api.localPlayer
import com.projectx.script.api.pressKey
import com.projectx.ui.backend.dsl.ImGuiDsl
import com.projectx.ui.backend.dsl.scopes.separator
import com.projectx.ui.backend.dsl.scopes.text
import com.projectx.util.formatElapsedTime
import world.gregs.voidps.cache.Cache
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ThreadLocalRandom

@ScriptDescription(
    name = "GE Flipper",
    version = "1.0.1",
    author = "Cryptic",
    description = "Flips items on the Grand Exchange: buys just above the latest instant-sell price and sells just below " +
        "the latest instant-buy price, using live prices from the RuneScape Wiki. Start it next to a Grand Exchange clerk.",
)
class GrandExchangeFlipper : Script(), ConfigurableScript {

    private val itemsSection = ConfigSection("Items")
    private val itemList = StringConfigItem(
        name = "Item list",
        description = "Comma-separated item names to flip, e.g. \"Feather, Yew logs\". Leave empty to pick the most " +
            "profitable items automatically.",
    )
    private val allowMembers = BooleanConfigItem("Members items", "Allow members-only items. Turn off on a free account.", true)
    private val minDailyVolume = IntConfigItem("Minimum daily volume", "Skip items that trade fewer than this many a day.", 25_000, 0)
    private val maxPriceEach = IntConfigItem("Maximum price each", "Skip items that cost more than this.", 5_000_000, 1)

    private val moneySection = ConfigSection("Money")
    private val slotsToUse = IntConfigItem("Slots to use", "Offer slots the flipper may fill. Free accounts have 3.", 3, 1, GrandExchange.SLOT_COUNT)
    private val maxCoinsPerOffer = IntConfigItem("Coins per offer", "Most coins a single buy offer may spend.", 2_000_000, 1)
    private val coinsToKeep = IntConfigItem("Coins to keep", "Never spend the money pouch below this.", 0, 0)
    private val minProfitPerItem = IntConfigItem("Minimum profit each", "Coins each flip must make per item after tax.", 2, 1)
    private val minProfitPerOffer = IntConfigItem("Minimum profit per offer", "Coins a whole offer must be expected to make after tax.", 2_000, 1)
    private val taxPercent = IntConfigItem("Sales tax %", "Share of each sale the exchange withholds (items under 50 coins are exempt).", 2, 0, 50)

    private val sellBackpackFirst = BooleanConfigItem(
        "Sell backpack first",
        "On start, sell every item in the backpack that trades on the exchange, at market, before buying anything.",
        true,
    )
    private val adoptExisting = BooleanConfigItem(
        "Take over existing offers",
        "On start, manage offers already on the exchange as if the flipper had placed them. Leave off if you trade on this account yourself.",
        false,
    )

    private val timingSection = ConfigSection("Timing")
    private val maxPriceAgeMinutes = IntConfigItem("Price age limit (minutes)", "Only trust an item whose last instant buy and sell are this recent.", 15, 1)
    private val repriceMinutes = IntConfigItem("Re-price after (minutes)", "Abort and re-price an offer that has not finished in this long.", 12, 1)
    private val sellAtLossMinutes = IntConfigItem("Accept a loss after (minutes)", "Once an item has been held this long, sell at market even below cost. 0 = never.", 90, 0)
    private val takeBreaks = BooleanConfigItem("Take breaks", "Pause for a few minutes now and then.", true)
    private val stopAfterMinutes = IntConfigItem("Stop after (minutes)", "0 = run until stopped.", 0, 0)

    private val ledger = FlipLedger()
    private val failedUntil = HashMap<Int, Long>()
    private val slotFailures = HashMap<Int, Int>()
    private val sellFailures = HashMap<Int, Int>()
    private val slotParkedUntil = HashMap<Int, Long>()
    private val startTime = System.currentTimeMillis()
    private var nextBreakAt = startTime + breakInterval()
    private var openFailures = 0
    private var restored = false
    private var savedState = ""
    private var idleReason: String? = null
    private var offerScreenSince = 0L

    @Volatile private var status = "Starting"
        set(value) {
            if (value != field) println("[GE Flipper] $value")
            field = value
        }

    override fun onStart() {
        GrandExchangePrices.userAgent = "ProjectX official-scripts GE Flipper (iEasyScript)"
        println("GE Flipper started")
    }

    override suspend fun loop() {
        if (stopAfterMinutes.value > 0 && System.currentTimeMillis() - startTime >= stopAfterMinutes.value * 60_000L) {
            status = "Finished the configured run time"
            stop()
            return
        }
        if (!GrandExchange.isOpen) {
            openExchange()
            return
        }
        if (!GrandExchange.isSupported) {
            status = "This client build's Grand Exchange state is not supported yet"
            stop()
            return
        }
        if (!awaitGrandExchangePrices()) {
            status = "Waiting for prices from the RuneScape Wiki"
            delay(5_000, 2_000)
            return
        }

        if (GrandExchange.isSettingUpOffer) {
            closeOfferScreen()
            return
        }
        offerScreenSince = 0L

        if (!restored) {
            restoreState()
            if (sellBackpackFirst.value) addBackpackStock()
            saveState()
            restored = true
        }

        settleFinished()
        idleReason = null
        val acted = collect() || repriceStale() || listHoldings() || placeBuy()
        saveState()
        if (!acted) {
            status = idleReason ?: "Waiting for offers to fill"
            if (maybeTakeBreak()) return
            delayBetween(2_500, 7_000)
        }
    }

    /**
     * The same screen is used to set up a new offer and to view one already placed. A half-finished setup is closed
     * straight away; an offer being viewed is most likely the player looking at it, so it is only closed if it stays
     * open.
     */
    private suspend fun closeOfferScreen() {
        val viewingPlacedOffer = !GrandExchange.offer(GrandExchange.setupSlot).isEmpty
        val now = System.currentTimeMillis()
        if (offerScreenSince == 0L) offerScreenSince = now
        if (viewingPlacedOffer && now - offerScreenSince < VIEWED_OFFER_GRACE_MILLIS) {
            status = "Waiting while an offer is being viewed"
            delayBetween(2_000, 4_000)
            return
        }
        status = if (viewingPlacedOffer) "Closing an offer left open" else "Closing a half-finished offer"
        pressKey(Key.ESCAPE)
        delayUntil(3_000) { !GrandExchange.isOpen }
        offerScreenSince = 0L
    }

    private fun stateFile(): File {
        val account = runCatching { localPlayer.name }.getOrDefault("").replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "unknown" }
        return File(System.getProperty("user.home"), ".projectx/script-data/ge-flipper/$account.txt")
    }

    /**
     * Picks up where the last run left off. A saved offer is only kept while the exchange still shows exactly that
     * offer in that slot; anything else was collected, cancelled or replaced while the flipper was not watching.
     */
    private fun restoreState() {
        val file = stateFile()
        if (file.isFile) ledger.restore(file.readText())
        for (placed in ledger.offers.toList()) {
            val live = GrandExchange.offer(placed.slot)
            val same = !live.isEmpty && live.itemId in GrandExchange.itemForms(placed.itemId) &&
                live.price == placed.price && live.quantity == placed.quantity && live.type.name == placed.side.name
            if (!same) ledger.forgetOffer(placed.slot)
        }
        if (adoptExisting.value) {
            for (live in GrandExchange.activeOffers()) {
                if (ledger.offer(live.slot) != null) continue
                ledger.adopt(live.slot, live.itemId, Side.valueOf(live.type.name), live.price, live.quantity, taxPercent.value)
            }
        }
        status = "Tracking ${ledger.offers.size} offers and ${ledger.held.size} held items"
        saveState()
    }

    private fun saveState() {
        val text = ledger.serialize()
        if (text == savedState) return
        runCatching {
            val file = stateFile()
            file.parentFile.mkdirs()
            file.writeText(text)
            savedState = text
        }.onFailure { println("[GE Flipper] could not save state: ${it.message}") }
    }

    private suspend fun openExchange() {
        status = "Opening the Grand Exchange"
        if (geOpen()) {
            openFailures = 0
            return
        }
        if (++openFailures >= 5) {
            status = "Could not open the Grand Exchange - start next to a clerk"
            stop()
            return
        }
        delay(3_000, 1_000)
    }

    /**
     * Reads what finished offers traded before collecting empties them. A slot this script placed that is now empty
     * without finishing was cancelled or collected by the player; it is forgotten so the flipper stops tracking it.
     */
    private fun settleFinished() {
        for (placed in ledger.offers.toList()) {
            val offer = GrandExchange.offer(placed.slot)
            when {
                offer.isEmpty || offer.itemId !in GrandExchange.itemForms(placed.itemId) -> ledger.forgetOffer(placed.slot)
                offer.isFinished -> settle(placed, offer)
            }
        }
    }

    private fun settle(placed: FlipLedger.Placed, offer: GrandExchangeOffer) {
        val name = itemName(placed.itemId)
        if (placed.side == Side.BUY) {
            ledger.settleBuy(placed.slot, offer.completedQuantity, offer.completedGold)
            status = "Bought ${format(offer.completedQuantity)}/${format(placed.quantity)} $name for ${format(offer.completedGold)}"
            return
        }
        val coins = GrandExchange.collectable(placed.slot).filter { it.id == COINS }.sumOf { it.amount.toLong() }
        val received = if (coins > 0) coins else offer.completedGold - FlipMath.tax(placed.price, taxPercent.value) * offer.completedQuantity
        ledger.settleSell(placed.slot, offer.completedQuantity, received)
        status = "Sold ${format(offer.completedQuantity)}/${format(placed.quantity)} $name for ${format(received)}, profit so far ${format(ledger.profit)}"
    }

    private suspend fun collect(): Boolean {
        if (!GrandExchange.hasCollectable()) return false
        status = "Collecting"
        geCollectAll()
        return true
    }

    private suspend fun repriceStale(): Boolean {
        val now = System.currentTimeMillis()
        val stale = ledger.offers.firstOrNull { now - it.placedAtMillis >= repriceMinutes.value * 60_000L && GrandExchange.offer(it.slot).isActive }
            ?: return false
        status = "Re-pricing ${itemName(stale.itemId)}"
        geAbort(stale.slot)
        return true
    }

    /** Lists the next held item that is not already on offer. Selling always gets a free slot before buying does. */
    private suspend fun listHoldings(): Boolean {
        val onOffer = ledger.offers.map { it.itemId }.toSet()
        for (holding in ledger.held.filter { it.itemId !in onOffer }) {
            val carried = inventory.count(*GrandExchange.itemForms(holding.itemId))
            if (carried <= 0) {
                println("[GE Flipper] ${itemName(holding.itemId)} is no longer in the backpack; no longer tracking it")
                ledger.dropHolding(holding.itemId)
                continue
            }
            val price = sellPrice(holding) ?: continue
            val slot = freeSlot() ?: return false
            val quantity = minOf(holding.quantity, carried)
            status = "Selling ${format(quantity)} ${itemName(holding.itemId)} at ${format(price)}"
            if (geSell(holding.itemId, quantity, price, slot)) {
                ledger.recordSell(slot, holding.itemId, price, quantity, taxPercent.value)
                slotFailures.remove(slot)
                sellFailures.remove(holding.itemId)
            } else {
                recordSlotFailure(slot)
                recordSellFailure(holding.itemId)
            }
            return true
        }
        return false
    }

    /** A held item that keeps failing to list would block buying forever, so after a few tries it is left in the backpack. */
    private fun recordSellFailure(itemId: Int) {
        val failures = (sellFailures[itemId] ?: 0) + 1
        sellFailures[itemId] = failures
        if (failures >= MAX_SELL_FAILURES) {
            println("[GE Flipper] Could not sell ${itemName(itemId)} after $failures tries; leaving it in the backpack")
            ledger.dropHolding(itemId)
            sellFailures.remove(itemId)
        }
    }

    /** Null while there is no market price to sell starting stock at, rather than guessing one. */
    private fun sellPrice(holding: FlipLedger.Holding): Long? {
        val marketPrice = market(holding.itemId)?.let { FlipMath.sellPrice(it) }
        if (holding.startingStock) return marketPrice
        val breakEven = FlipMath.breakEvenPrice(holding.costEach, taxPercent.value)
        if (marketPrice == null) return breakEven
        val heldMinutes = (System.currentTimeMillis() - holding.sinceMillis) / 60_000L
        val acceptLoss = sellAtLossMinutes.value > 0 && heldMinutes >= sellAtLossMinutes.value
        return if (acceptLoss) marketPrice else maxOf(marketPrice, breakEven)
    }

    /**
     * Records what the backpack already holds as starting stock, so it is sold before anything new is bought. Coins
     * and items the exchange has no price for are left alone.
     */
    private fun addBackpackStock() {
        val carried = HashMap<Int, Int>()
        for (item in inventory) {
            if (item.id == COINS || item.amount <= 0) continue
            val obj = Cache.obj(item.id) ?: continue
            val itemId = if (obj.noted && obj.notedItemId > 0) obj.notedItemId else item.id
            if (GrandExchangePrices.item(itemId) == null || market(itemId) == null) continue
            carried[itemId] = (carried[itemId] ?: 0) + item.amount
        }
        for ((itemId, quantity) in carried) ledger.addStartingStock(itemId, quantity)
        if (carried.isNotEmpty()) {
            status = "Selling the backpack first: ${carried.entries.joinToString { "${format(it.value)} ${itemName(it.key)}" }}"
        }
    }

    private suspend fun placeBuy(): Boolean {
        if (ledger.held.isNotEmpty()) {
            idleReason = "Selling held items before buying: ${ledger.held.joinToString { itemName(it.itemId) }}"
            return false
        }
        val slot = freeSlot() ?: return false
        val pouch = pouchCoins()
        val spendable = minOf(maxCoinsPerOffer.value.toLong(), pouch - coinsToKeep.value)
        if (spendable <= 0) {
            idleReason = "Spending limit reached: ${format(pouch)} in the pouch, keeping ${format(coinsToKeep.value)}"
            return false
        }
        val plan = bestPlan(spendable)
        if (plan == null) {
            idleReason = "No item meets the filters with ${format(spendable)} to spend"
            return false
        }
        status = "Buying ${format(plan.quantity)} ${plan.market.name} at ${format(plan.buyPrice)}"
        if (geBuy(plan.market.itemId, plan.quantity, plan.buyPrice, slot)) {
            ledger.recordBuy(slot, plan.market.itemId, plan.buyPrice, plan.quantity)
            slotFailures.remove(slot)
        } else {
            markFailed(plan.market.itemId)
            recordSlotFailure(slot)
        }
        return true
    }

    /** A slot that keeps failing is most likely locked (a free account's extra slots), so it is left alone for a while. */
    private fun recordSlotFailure(slot: Int) {
        val failures = (slotFailures[slot] ?: 0) + 1
        slotFailures[slot] = failures
        if (failures >= 2) {
            slotParkedUntil[slot] = System.currentTimeMillis() + SLOT_PARK_MILLIS
            slotFailures.remove(slot)
        }
    }

    private fun bestPlan(spendable: Long): FlipPlan? {
        val settings = settings()
        val nowSeconds = System.currentTimeMillis() / 1000
        val now = System.currentTimeMillis()
        return candidateItems()
            .asSequence()
            .filter { !ledger.isBusyWith(it) && (failedUntil[it] ?: 0L) < now }
            .mapNotNull { itemId ->
                val market = market(itemId) ?: return@mapNotNull null
                val limit = ledger.limitRemaining(itemId, market.buyLimit ?: FlipMath.DEFAULT_BUY_LIMIT)
                FlipMath.plan(market, settings, nowSeconds, limit, spendable)
            }
            .maxByOrNull { it.expectedProfit }
    }

    private fun candidateItems(): Collection<Int> {
        val names = itemList.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return GrandExchangePrices.items().map { it.id }
        return names.mapNotNull { GrandExchangePrices.item(it)?.id }
    }

    private fun market(itemId: Int): Market? {
        val item = GrandExchangePrices.item(itemId) ?: return null
        val price = GrandExchangePrices.price(itemId) ?: return null
        return Market(
            itemId = itemId,
            name = item.name,
            members = item.members,
            instantBuy = price.high ?: return null,
            instantBuyTime = price.highTime ?: return null,
            instantSell = price.low ?: return null,
            instantSellTime = price.lowTime ?: return null,
            buyLimit = item.buyLimit,
            dailyVolume = GrandExchangePrices.dailyVolume(itemId) ?: 0,
        )
    }

    private fun settings() = FlipSettings(
        taxPercent = taxPercent.value,
        minProfitPerItem = minProfitPerItem.value.toLong(),
        minProfitPerOffer = minProfitPerOffer.value.toLong(),
        maxPriceEach = maxPriceEach.value.toLong(),
        minDailyVolume = minDailyVolume.value.toLong(),
        maxPriceAgeSeconds = maxPriceAgeMinutes.value * 60L,
        allowMembers = allowMembers.value,
    )

    /** An empty slot, while the flipper is still under its own slot allowance. */
    private fun freeSlot(): Int? {
        if (ledger.offers.size >= slotsToUse.value) {
            idleReason = "Using all ${slotsToUse.value} allowed slots"
            return null
        }
        val now = System.currentTimeMillis()
        val slot = GrandExchange.offers().firstOrNull { it.isEmpty && (slotParkedUntil[it.slot] ?: 0L) < now }?.slot
        if (slot == null) idleReason = "No free slot on the exchange"
        return slot
    }

    private fun markFailed(itemId: Int) {
        failedUntil[itemId] = System.currentTimeMillis() + FAILURE_COOLDOWN_MILLIS
    }

    private suspend fun maybeTakeBreak(): Boolean {
        if (!takeBreaks.value || System.currentTimeMillis() < nextBreakAt) return false
        val minutes = ThreadLocalRandom.current().nextInt(2, 6)
        status = "Taking a $minutes minute break"
        delay(minutes * 60_000, 20_000)
        nextBreakAt = System.currentTimeMillis() + breakInterval()
        return true
    }

    private fun breakInterval(): Long = ThreadLocalRandom.current().nextLong(25, 50) * 60_000L

    private fun pouchCoins(): Long = runCatching { coinPouch.count(COINS).toLong() }.getOrDefault(0L)

    override fun render() {
        val now = System.currentTimeMillis()
        ImGuiDsl.window("GE Flipper") {
            text(status)
            separator()
            text("Runtime: ${formatElapsedTime(now, startTime)}")
            val hours = ((now - startTime) / 3_600_000.0).coerceAtLeast(1.0 / 60)
            text("Profit: ${format(ledger.profit)} (${format((ledger.profit / hours).toLong())}/hr)")
            text("Items sold: ${format(ledger.itemsSold)}")
            text("Money pouch: ${format(pouchCoins())}")
            separator()
            if (ledger.offers.isEmpty()) text("No offers placed")
            for (placed in ledger.offers) {
                val offer = GrandExchange.offer(placed.slot)
                val age = (now - placed.placedAtMillis) / 60_000L
                text(
                    "Slot ${placed.slot + 1}: ${placed.side} ${itemName(placed.itemId)} " +
                        "${format(offer.completedQuantity)}/${format(placed.quantity)} @ ${format(placed.price)} (${age}m)"
                )
            }
            for (holding in ledger.held) {
                text("Holding ${format(holding.quantity)} ${itemName(holding.itemId)} (cost ${format(holding.costEach)} each)")
            }
        }
    }

    private fun itemName(itemId: Int): String = GrandExchangePrices.item(itemId)?.name ?: Cache.obj(itemId)?.name ?: "item $itemId"

    private fun format(value: Number): String = NumberFormat.getNumberInstance(Locale.US).format(value)

    private companion object {
        const val COINS = 995
        const val FAILURE_COOLDOWN_MILLIS = 10 * 60_000L
        const val SLOT_PARK_MILLIS = 30 * 60_000L
        const val VIEWED_OFFER_GRACE_MILLIS = 60_000L
        const val MAX_SELL_FAILURES = 3
    }
}
