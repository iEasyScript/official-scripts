package com.projectx.script.impl.geflipper

enum class Side { BUY, SELL }

/**
 * Everything the flipper manages: the offers it placed, the items it holds and has not sold yet, how much of each buy
 * limit it has used, and its profit. Items the player already carried are only in here as starting stock, which is
 * sold at market without counting towards profit.
 */
class FlipLedger(private val clock: () -> Long = System::currentTimeMillis) {

    data class Placed(
        val slot: Int,
        val itemId: Int,
        val side: Side,
        val price: Long,
        val quantity: Int,
        val placedAtMillis: Long,
        /** For a sell, what each item cost to buy. */
        val costEach: Long,
        /** For a sell, when the items were bought, which survives re-pricing. */
        val heldSinceMillis: Long,
        val startingStock: Boolean = false,
    )

    data class Holding(
        val itemId: Int,
        val quantity: Int,
        val totalCost: Long,
        val sinceMillis: Long,
        /** Carried before the flipper started, so its cost is unknown. */
        val startingStock: Boolean = false,
    ) {
        val costEach: Long get() = if (quantity > 0) (totalCost + quantity - 1) / quantity else 0
    }

    /** A buy limit's timer starts at the first purchase and resets everything bought once it runs out. */
    private data class LimitWindow(val startMillis: Long, val bought: Int)

    private val placed = LinkedHashMap<Int, Placed>()
    private val holdings = LinkedHashMap<Int, Holding>()
    private val limits = HashMap<Int, LimitWindow>()

    var profit: Long = 0
        private set
    var itemsSold: Long = 0
        private set

    val offers: Collection<Placed> get() = placed.values
    val held: Collection<Holding> get() = holdings.values.filter { it.quantity > 0 }

    fun offer(slot: Int): Placed? = placed[slot]

    fun isBusyWith(itemId: Int): Boolean = placed.values.any { it.itemId == itemId } || (holdings[itemId]?.quantity ?: 0) > 0

    fun recordBuy(slot: Int, itemId: Int, price: Long, quantity: Int) {
        val now = clock()
        placed[slot] = Placed(slot, itemId, Side.BUY, price, quantity, now, 0, now)
    }

    /** Items the player carried when the flipper started, to be sold before anything new is bought. */
    fun addStartingStock(itemId: Int, quantity: Int) {
        if (quantity <= 0 || isBusyWith(itemId)) return
        holdings[itemId] = Holding(itemId, quantity, 0, clock(), startingStock = true)
    }

    /**
     * Moves [quantity] of a holding onto the exchange. Starting stock is booked at what the sale will pay after tax,
     * so selling it adds nothing to profit.
     */
    fun recordSell(slot: Int, itemId: Int, price: Long, quantity: Int, taxPercent: Int) {
        val holding = holdings[itemId] ?: return
        val costEach = if (holding.startingStock) price - FlipMath.tax(price, taxPercent) else holding.costEach
        placed[slot] = Placed(slot, itemId, Side.SELL, price, quantity, clock(), costEach, holding.sinceMillis, holding.startingStock)
        val left = (holding.quantity - quantity).coerceAtLeast(0)
        holdings[itemId] = holding.copy(quantity = left, totalCost = if (holding.startingStock) 0 else costEach * left)
    }

    /** A buy offer finished: [bought] items arrived for [spent] coins. */
    fun settleBuy(slot: Int, bought: Int, spent: Long) {
        val offer = placed.remove(slot) ?: return
        if (bought <= 0) return
        val now = clock()
        val window = currentWindow(offer.itemId)
        limits[offer.itemId] = if (window == null) LimitWindow(now, bought) else window.copy(bought = window.bought + bought)
        val holding = holdings[offer.itemId]
        holdings[offer.itemId] = if (holding == null || holding.quantity == 0) {
            Holding(offer.itemId, bought, spent, now)
        } else {
            holding.copy(quantity = holding.quantity + bought, totalCost = holding.totalCost + spent)
        }
    }

    /** A sell offer finished: [sold] items went for [received] coins after tax; the rest came back. */
    fun settleSell(slot: Int, sold: Int, received: Long) {
        val offer = placed.remove(slot) ?: return
        profit += received - offer.costEach * sold
        itemsSold += sold
        val unsold = offer.quantity - sold
        if (unsold <= 0) return
        val returnedCost = if (offer.startingStock) 0 else offer.costEach * unsold
        val holding = holdings[offer.itemId]
        holdings[offer.itemId] = if (holding == null || holding.quantity == 0) {
            Holding(offer.itemId, unsold, returnedCost, offer.heldSinceMillis, offer.startingStock)
        } else {
            holding.copy(quantity = holding.quantity + unsold, totalCost = holding.totalCost + returnedCost)
        }
    }

    /** Forgets an offer the player cancelled or collected themselves, and the items of a holding that are gone. */
    fun forgetOffer(slot: Int) {
        placed.remove(slot)
    }

    fun dropHolding(itemId: Int) {
        holdings.remove(itemId)
    }

    /** What is left of [buyLimit] for [itemId], counting both completed purchases and open buy offers. */
    fun limitRemaining(itemId: Int, buyLimit: Int): Int {
        val bought = currentWindow(itemId)?.bought ?: 0
        val pending = placed.values.filter { it.itemId == itemId && it.side == Side.BUY }.sumOf { it.quantity }
        return (buyLimit - bought - pending).coerceAtLeast(0)
    }

    /** Takes over an offer that was already on the exchange. A sell's cost is unknown, so it is booked at break even. */
    fun adopt(slot: Int, itemId: Int, side: Side, price: Long, quantity: Int, taxPercent: Int) {
        val now = clock()
        val costEach = if (side == Side.SELL) price - FlipMath.tax(price, taxPercent) else 0
        placed[slot] = Placed(slot, itemId, side, price, quantity, now, costEach, now)
    }

    /** One record per line: `offer`, `holding`, `limit`, `profit` and `sold`. */
    fun serialize(): String = buildString {
        for (p in placed.values) {
            appendLine("offer ${p.slot} ${p.itemId} ${p.side} ${p.price} ${p.quantity} ${p.placedAtMillis} ${p.costEach} ${p.heldSinceMillis} ${p.startingStock}")
        }
        for (h in holdings.values) appendLine("holding ${h.itemId} ${h.quantity} ${h.totalCost} ${h.sinceMillis} ${h.startingStock}")
        for ((itemId, w) in limits) appendLine("limit $itemId ${w.startMillis} ${w.bought}")
        appendLine("profit $profit")
        appendLine("sold $itemsSold")
    }

    fun restore(text: String) {
        placed.clear()
        holdings.clear()
        limits.clear()
        for (line in text.lineSequence()) {
            val f = line.trim().split(' ')
            runCatching {
                when (f[0]) {
                    "offer" -> placed[f[1].toInt()] = Placed(
                        f[1].toInt(), f[2].toInt(), Side.valueOf(f[3]), f[4].toLong(), f[5].toInt(), f[6].toLong(), f[7].toLong(), f[8].toLong(),
                        f.getOrNull(9).toBoolean(),
                    )
                    "holding" -> holdings[f[1].toInt()] = Holding(f[1].toInt(), f[2].toInt(), f[3].toLong(), f[4].toLong(), f.getOrNull(5).toBoolean())
                    "limit" -> limits[f[1].toInt()] = LimitWindow(f[2].toLong(), f[3].toInt())
                    "profit" -> profit = f[1].toLong()
                    "sold" -> itemsSold = f[1].toLong()
                }
            }
        }
    }

    private fun currentWindow(itemId: Int): LimitWindow? {
        val window = limits[itemId] ?: return null
        if (clock() - window.startMillis < FlipMath.BUY_LIMIT_WINDOW_MILLIS) return window
        limits.remove(itemId)
        return null
    }
}
