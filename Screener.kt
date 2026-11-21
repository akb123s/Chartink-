package com.example.intradayscreener

object Screener {

    fun toRow(s: RawStock): ScreenRow? {
        if (s.low52 <= 0 || s.high52 <= 0 || s.avgVolume1w <= 0 || s.ltp <= 0) return null
        return ScreenRow(
            symbol = s.symbol,
            name = s.name,
            ltp = s.ltp,
            marketCapCr = s.marketCapCr,
            pctChange = s.dayChangePct,
            volChange = (s.volume - s.avgVolume1w) / s.avgVolume1w * 100.0,
            upFromLow = (s.ltp - s.low52) / s.low52 * 100.0,
            downFromHigh = (s.high52 - s.ltp) / s.high52 * 100.0,
        )
    }

    fun matches(r: ScreenRow): Boolean =
        r.marketCapCr in Rules.MCAP_MIN_CR..Rules.MCAP_MAX_CR &&
            r.volChange > Rules.VOL_CHANGE_MIN &&
            r.upFromLow > Rules.UP_FROM_LOW_MIN &&
            r.downFromHigh > Rules.DOWN_FROM_HIGH_MIN

    fun run(raw: List<RawStock>): List<ScreenRow> =
        raw.mapNotNull(::toRow).filter(::matches).sortedByDescending { it.pctChange }
}
