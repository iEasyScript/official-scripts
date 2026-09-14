package com.projectx.script.impl.geflipper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlipLedgerTest {
    private var now = 0L
    private val ledger = FlipLedger { now }

    @Test
    fun `a filled buy becomes a holding at what it actually cost`() {
        ledger.recordBuy(slot = 0, itemId = 7, price = 110, quantity = 10)
        ledger.settleBuy(slot = 0, bought = 10, spent = 1_050)

        val holding = ledger.held.single()
        assertEquals(10, holding.quantity)
        assertEquals(105, holding.costEach)
        assertTrue(ledger.offers.isEmpty())
    }

    @Test
    fun `a sale books profit against cost and returns the unsold part`() {
        ledger.recordBuy(0, 7, 100, 10)
        ledger.settleBuy(0, 10, 1_000)
        ledger.recordSell(slot = 1, itemId = 7, price = 150, quantity = 10, taxPercent = 2)
        assertTrue(ledger.held.isEmpty())

        ledger.settleSell(slot = 1, sold = 6, received = 6 * 147)

        assertEquals(6L * 147 - 6 * 100, ledger.profit)
        assertEquals(6, ledger.itemsSold)
        assertEquals(4, ledger.held.single().quantity)
        assertEquals(100, ledger.held.single().costEach)
    }

    @Test
    fun `holding time survives re-pricing a sale`() {
        now = 1_000
        ledger.recordBuy(0, 7, 100, 5)
        ledger.settleBuy(0, 5, 500)
        now = 9_000
        ledger.recordSell(1, 7, 150, 5, 2)
        now = 20_000
        ledger.settleSell(1, 0, 0)

        assertEquals(1_000, ledger.held.single().sinceMillis)
    }

    @Test
    fun `buy limit counts purchases and open offers until four hours after the first purchase`() {
        ledger.recordBuy(0, 7, 100, 30)
        assertEquals(70, ledger.limitRemaining(7, 100))

        ledger.settleBuy(0, 30, 3_000)
        now += FlipMath.BUY_LIMIT_WINDOW_MILLIS - 1
        ledger.recordBuy(1, 7, 100, 20)
        ledger.settleBuy(1, 20, 2_000)
        assertEquals(50, ledger.limitRemaining(7, 100))

        now += 1
        assertEquals(100, ledger.limitRemaining(7, 100))
    }

    @Test
    fun `state survives a save and restore`() {
        now = 5_000
        ledger.recordBuy(0, 7, 100, 10)
        ledger.settleBuy(0, 10, 1_000)
        ledger.recordSell(1, 7, 150, 4, 2)
        ledger.settleSell(1, 4, 588)
        ledger.recordBuy(2, 9, 20, 50)
        ledger.addStartingStock(11, 300)

        val restored = FlipLedger { now }
        restored.restore(ledger.serialize())

        assertEquals(ledger.serialize(), restored.serialize())
        assertEquals(ledger.profit, restored.profit)
        assertEquals(6, restored.held.first { it.itemId == 7 }.quantity)
        assertTrue(restored.held.first { it.itemId == 11 }.startingStock)
        assertEquals(90, restored.limitRemaining(7, 100))
        assertEquals(Side.BUY, restored.offer(2)?.side)
    }

    @Test
    fun `restores state saved before starting stock existed`() {
        val restored = FlipLedger { now }
        restored.restore("offer 0 7 BUY 100 10 1 0 1\nholding 9 5 500 1\nprofit 12\nsold 3\n")

        assertEquals(Side.BUY, restored.offer(0)?.side)
        assertFalse(restored.held.single().startingStock)
        assertEquals(12, restored.profit)
    }

    @Test
    fun `starting stock sells without counting towards profit, and what does not sell stays starting stock`() {
        ledger.addStartingStock(itemId = 11, quantity = 300)
        ledger.recordSell(slot = 0, itemId = 11, price = 1_000, quantity = 300, taxPercent = 2)
        assertTrue(ledger.held.isEmpty())

        ledger.settleSell(slot = 0, sold = 200, received = 200 * 980)

        assertEquals(0, ledger.profit)
        val back = ledger.held.single()
        assertEquals(100, back.quantity)
        assertTrue(back.startingStock)
    }

    @Test
    fun `starting stock is not added for an item the flipper already tracks`() {
        ledger.recordBuy(0, 7, 100, 10)
        ledger.addStartingStock(7, 50)
        assertTrue(ledger.held.isEmpty())
    }

    @Test
    fun `an item is busy while it is on offer or held`() {
        assertFalse(ledger.isBusyWith(7))
        ledger.recordBuy(0, 7, 100, 1)
        assertTrue(ledger.isBusyWith(7))
        ledger.settleBuy(0, 0, 0)
        assertFalse(ledger.isBusyWith(7))
    }
}
