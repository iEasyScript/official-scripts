package com.projectx.script.impl.geflipper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlipMathTest {
    private val now = 1_000_000L

    private val settings = FlipSettings(
        taxPercent = 2,
        minProfitPerItem = 1,
        minProfitPerOffer = 1,
        maxPriceEach = 10_000_000,
        minDailyVolume = 0,
        maxPriceAgeSeconds = 600,
        allowMembers = true,
    )

    private fun market(instantSell: Long, instantBuy: Long, limit: Int? = 1_000, volume: Long = 240_000, members: Boolean = false, age: Long = 0) =
        Market(1, "Test", members, instantBuy, now - age, instantSell, now - age, limit, volume)

    @Test
    fun `tax is rounded down and waived below fifty coins`() {
        assertEquals(99, FlipMath.tax(4_992, 2))
        assertEquals(0, FlipMath.tax(49, 2))
        assertEquals(1, FlipMath.tax(50, 2))
    }

    @Test
    fun `break even covers the cost after tax and is the lowest such price`() {
        for (cost in listOf(1L, 49L, 50L, 51L, 999L, 4_893L, 1_234_567L)) {
            val price = FlipMath.breakEvenPrice(cost, 2)
            assertTrue(price - FlipMath.tax(price, 2) >= cost, "price $price must cover $cost")
            assertTrue(price == 1L || price - 1 - FlipMath.tax(price - 1, 2) < cost, "price $price must be the lowest for $cost")
        }
    }

    @Test
    fun `buys one above the instant sell and sells one below the instant buy`() {
        val plan = assertNotNull(FlipMath.plan(market(1_000, 1_100), settings, now, 1_000, 10_000_000))
        assertEquals(1_001, plan.buyPrice)
        assertEquals(1_099, plan.sellPrice)
        assertEquals(1_099 - 21 - 1_001, plan.profitPerItem)
    }

    @Test
    fun `quantity is capped by buy limit, coins and an hour of volume`() {
        assertEquals(50, FlipMath.plan(market(1_000, 1_100), settings, now, 50, 10_000_000)?.quantity)
        assertEquals(9, FlipMath.plan(market(1_000, 1_100), settings, now, 1_000, 9_999)?.quantity)
        assertEquals(10, FlipMath.plan(market(1_000, 1_100, volume = 240), settings, now, 1_000, 10_000_000)?.quantity)
    }

    @Test
    fun `rejects stale, thin, unaffordable and unprofitable markets`() {
        assertNull(FlipMath.plan(market(1_000, 1_100, age = 601), settings, now, 1_000, 10_000_000))
        assertNull(FlipMath.plan(market(1_000, 1_020), settings, now, 1_000, 10_000_000))
        assertNull(FlipMath.plan(market(1_000, 1_100), settings, now, 1_000, 500))
        assertNull(FlipMath.plan(market(1_000, 1_100), settings, now, 0, 10_000_000))
        assertNull(FlipMath.plan(market(1_000, 1_100, members = true), settings.copy(allowMembers = false), now, 1_000, 10_000_000))
        assertNull(FlipMath.plan(market(1_000, 1_100, volume = 10), settings.copy(minDailyVolume = 11), now, 1_000, 10_000_000))
        assertNull(FlipMath.plan(market(1_000, 1_100), settings.copy(minProfitPerOffer = 1_000_000), now, 1_000, 10_000_000))
    }
}
