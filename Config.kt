package com.example.intradayscreener

/** Screener rules from the requirement. Edit here to change the filter. */
object Rules {
    const val MCAP_MIN_CR = 1_000.0
    const val MCAP_MAX_CR = 15_000_000.0
    const val VOL_CHANGE_MIN = 50.0      // v% > 50
    const val UP_FROM_LOW_MIN = 15.0     // l% > 15
    const val DOWN_FROM_HIGH_MIN = 5.0   // h% > 5
}

/**
 * Groww has no public API. Open https://groww.in/stocks/intraday in Chrome,
 * DevTools > Network > Fetch/XHR, find the request that returns the stock list,
 * then fill these in. While ENDPOINT is blank the app runs on sample data.
 */
object SourceConfig {
    const val ENDPOINT = ""                    // e.g. "https://groww.in/v1/api/..."
    const val HTTP_METHOD = "GET"              // "GET" or "POST"
    const val POST_BODY = ""                   // JSON body when POST
    const val MCAP_TO_CRORE_DIVISOR = 1.0      // use 1e7 if the API returns rupees
    const val REFRESH_MS = 60_000L

    // JSON key aliases tried in order for each field.
    val NAME = listOf("companyName", "name", "symbol", "searchId")
    val SYMBOL = listOf("nseScriptCode", "symbol", "searchId", "ticker")
    val LTP = listOf("ltp", "price", "lastPrice", "close")
    val DAY_CHANGE_PCT = listOf("dayChangePerc", "changePercent", "pChange", "dayChangePercent")
    val HIGH_52W = listOf("high52", "yearHigh", "week52High", "fiftyTwoWeekHigh")
    val LOW_52W = listOf("low52", "yearLow", "week52Low", "fiftyTwoWeekLow")
    val VOLUME = listOf("volume", "todayVolume", "totalTradedVolume")
    val AVG_VOLUME_1W = listOf("avgVolume1w", "oneWeekAvgVolume", "avgVolumeWeek", "avgVolume")
    val MARKET_CAP = listOf("marketCap", "mcap", "marketCapCr")
}
