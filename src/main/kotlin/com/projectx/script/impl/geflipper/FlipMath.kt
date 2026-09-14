package com.projectx.script.impl.geflipper

/** One item's live market, as the flipper sees it. Times are epoch seconds. */
data class Market(
    val itemId: Int,
    val name: String,
    val members: Boolean,
    val instantBuy: Long,
    val instantBuyTime: Long,
    val instantSell: Long,
    val instantSellTime: Long,
    val buyLimit: Int?,
    val dailyVolume: Long,
)

data class FlipSettings(
    val taxPercent: Int,
    val minProfitPerItem: Long,
    val minProfitPerOffer: Long,
    val maxPriceEach: Long,
    val minDailyVolume: Long,
    val maxPriceAgeSeconds: Long,
    val allowMembers: Boolean,
)

data class FlipPlan(val market: Market, val buyPrice: Long, val sellPrice: Long, val quantity: Int, val taxPercent: Int) {
    val profitPerItem: Long get() = FlipMath.profitPerItem(buyPrice, sellPrice, taxPercent)
    val expectedProfit: Long get() = profitPerItem * quantity
}

object FlipMath {
    /** Used when the wiki does not know an item's buy limit; low enough to stay under almost every real one. */
    const val DEFAULT_BUY_LIMIT = 100
    const val BUY_LIMIT_WINDOW_MILLIS = 4 * 60 * 60 * 1000L

    /** Items sold for less than this each are exempt from the exchange's sales tax. */
    const val TAX_FREE_BELOW = 50L

    /** The exchange withholds [percent] of each item's sale price, rounded down per item. */
    fun tax(sellPrice: Long, percent: Int): Long = if (sellPrice < TAX_FREE_BELOW) 0 else sellPrice * percent / 100

    /** A buy fills when someone sells instantly, so it only has to beat the last instant sell. */
    fun buyPrice(market: Market): Long = market.instantSell + 1

    /** A sell fills when someone buys instantly, so it only has to undercut the last instant buy. */
    fun sellPrice(market: Market): Long = (market.instantBuy - 1).coerceAtLeast(1)

    fun profitPerItem(buyPrice: Long, sellPrice: Long, taxPercent: Int): Long =
        sellPrice - tax(sellPrice, taxPercent) - buyPrice

    /** The lowest price whose after-tax proceeds still cover [costEach]. */
    fun breakEvenPrice(costEach: Long, taxPercent: Int): Long {
        if (costEach <= 0) return 1
        if (costEach < TAX_FREE_BELOW) return costEach
        var price = (costEach * 100 + (99 - taxPercent)) / (100 - taxPercent.coerceAtMost(99))
        while (price - tax(price, taxPercent) < costEach) price++
        while (price > 1 && price - 1 - tax(price - 1, taxPercent) >= costEach) price--
        return price
    }

    fun isTradable(market: Market, settings: FlipSettings, nowSeconds: Long): Boolean =
        market.instantBuy > market.instantSell &&
            market.instantSell > 0 &&
            market.instantBuy <= settings.maxPriceEach &&
            market.dailyVolume >= settings.minDailyVolume &&
            (settings.allowMembers || !market.members) &&
            nowSeconds - market.instantBuyTime <= settings.maxPriceAgeSeconds &&
            nowSeconds - market.instantSellTime <= settings.maxPriceAgeSeconds

    /**
     * How much of [market] to buy, or null when it is not worth a slot. The quantity is capped by what is left of
     * the buy limit, what can be afforded from [spendable], and about an hour's trading volume so the offer can fill.
     */
    fun plan(market: Market, settings: FlipSettings, nowSeconds: Long, limitRemaining: Int, spendable: Long): FlipPlan? {
        if (!isTradable(market, settings, nowSeconds)) return null
        val buy = buyPrice(market)
        val sell = sellPrice(market)
        if (sell <= buy) return null
        if (profitPerItem(buy, sell, settings.taxPercent) < settings.minProfitPerItem) return null
        val hourlyVolume = (market.dailyVolume / 24).coerceAtLeast(1)
        val quantity = minOf(limitRemaining.toLong(), spendable / buy, hourlyVolume, Int.MAX_VALUE.toLong()).toInt()
        if (quantity < 1) return null
        val plan = FlipPlan(market, buy, sell, quantity, settings.taxPercent)
        return plan.takeIf { it.expectedProfit >= settings.minProfitPerOffer }
    }
}
