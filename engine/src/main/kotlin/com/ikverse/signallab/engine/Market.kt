package com.ikverse.signallab.engine

import kotlin.math.pow

/**
 * One market's settings in one place: how many sessions make a year, what a round trip costs, what idle money earns,
 * and the exchange's rules that decide whether a paper order can fill. Nothing else in the engine may assume 365 days or
 * Binance's fees for a market that has its own entry here.
 */
class Market(
    val id: String,
    /** The currency the paper account is kept in. */
    val currency: String,
    /** Sessions in a year: 365 for crypto, which never closes; 245 for the Egyptian Exchange. */
    val sessionsPerYear: Int,
    /** Exchange fees and stamp duty for a round trip, before the spread. */
    val fees: Double,
    /** The whole round trip (fees plus the spread) for the busiest names, ranks 1 to [busiestRanks]. */
    private val roundTripBusiest: Double,
    /** The whole round trip for every other name. */
    private val roundTripOthers: Double,
    val busiestRanks: Int,
    /** What idle money earns a year, after tax, before the owner changes it in Settings. */
    val cashRate: Double,
    /** The tax taken from a cash dividend before it reaches the account. */
    val dividendTax: Double,
    /** The most a price may move in one session; 0 for none. */
    val priceLimit: Double,
    /**
     * A session that traded at one price all day and moved at least this far is taken as locked at the limit: nobody
     * was on the other side, so a paper order in that direction cannot fill. 0 when the market has no limit.
     */
    val lockedMove: Double,
    /** The smallest price step an order may use, for showing levels a person can type in. */
    val priceStep: Double,
) {
    /** The round trip for a name at [rank] (1 = the busiest); null rank counts as one of the others. */
    fun roundTrip(rank: Int?): Double = if (rank != null && rank in 1..busiestRanks) roundTripBusiest else roundTripOthers

    /** One session's interest on idle money at [yearlyRate]. */
    fun sessionRate(yearlyRate: Double): Double = (1 + yearlyRate).pow(1.0 / sessionsPerYear) - 1

    /** [price] rounded to the nearest price step. */
    fun round(price: Double): Double = if (priceStep > 0) Math.round(price / priceStep) * priceStep else price
}

object Markets {
    /** Binance spot: the costs of [EngineConfig.costFor], no interest on idle money, no limits. */
    val CRYPTO = Market(
        id = "crypto", currency = "USDT", sessionsPerYear = 365, fees = 2 * EngineConfig.DEFAULT_FEE_PER_SIDE,
        roundTripBusiest = EngineConfig.COST_MAJORS, roundTripOthers = EngineConfig.COST_OTHERS, busiestRanks = 2,
        cashRate = 0.0, dividendTax = 0.0, priceLimit = 0.0, lockedMove = 0.0, priceStep = 0.0,
    )

    /**
     * The Egyptian Exchange as the owner trades it on Thndr's Trader plan (docs/egx-plan.md part 4): 0.03% fees and 0.05%
     * stamp duty a side, commission waived on 50 orders a month, so 0.16% a round trip before the spread; with the spread,
     * 0.5% in all for the 30 busiest stocks and 0.8% for the rest. A money-market fund paid about 19% after tax. Dividends
     * are taxed 5%. A session's move is capped at 20%.
     */
    val EGX = Market(
        id = "egx", currency = "EGP", sessionsPerYear = 245, fees = 0.0016,
        roundTripBusiest = 0.005, roundTripOthers = 0.008, busiestRanks = 30,
        cashRate = 0.19, dividendTax = 0.05, priceLimit = 0.20, lockedMove = 0.045, priceStep = 0.01,
    )
}
