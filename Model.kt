package com.example.intradayscreener

data class RawStock(
    val symbol: String,
    val name: String,
    val ltp: Double,
    val dayChangePct: Double,
    val high52: Double,
    val low52: Double,
    val volume: Double,
    val avgVolume1w: Double,
    val marketCapCr: Double,
)

/** One screener result row with the four required columns. */
data class ScreenRow(
    val symbol: String,
    val name: String,
    val ltp: Double,
    val marketCapCr: Double,
    val pctChange: Double,    // %c  day change
    val volChange: Double,    // v%  today's volume vs 1-week average volume
    val upFromLow: Double,    // l%  up from 52-week low
    val downFromHigh: Double, // h%  down from 52-week high
)
