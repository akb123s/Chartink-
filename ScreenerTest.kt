package com.example.intradayscreener

import org.junit.Assert.*
import org.junit.Test

class ScreenerTest {
    private val ok = RawStock("A", "A", 250.0, 4.8, 280.0, 180.0, 3_000_000.0, 1_500_000.0, 5200.0)

    @Test fun passesAllRules() = assertEquals(1, Screener.run(listOf(ok)).size)
    @Test fun computesColumns() {
        val r = Screener.toRow(ok)!!
        assertEquals(100.0, r.volChange, 0.001)
        assertEquals(38.888, r.upFromLow, 0.01)
        assertEquals(10.714, r.downFromHigh, 0.01)
    }
    @Test fun failsMcap() = assertTrue(Screener.run(listOf(ok.copy(marketCapCr = 500.0))).isEmpty())
    @Test fun failsVolume() = assertTrue(Screener.run(listOf(ok.copy(volume = 2_000_000.0))).isEmpty())
    @Test fun failsLow() = assertTrue(Screener.run(listOf(ok.copy(ltp = 200.0))).isEmpty())
    @Test fun failsHigh() = assertTrue(Screener.run(listOf(ok.copy(ltp = 275.0))).isEmpty())
}
