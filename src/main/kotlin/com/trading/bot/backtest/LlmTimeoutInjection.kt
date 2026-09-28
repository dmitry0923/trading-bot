package com.trading.bot.backtest

/**
 * Детерминированная инъекция таймаутов LLM в бэктест-конвейере (research).
 *
 * Моделирует тот отказ, который в live приводит к fail-closed HOLD: вызов LLM
 * не уложился в бюджет `bt.agent.signal-budget-ms`
 * ([com.trading.bot.application.strategy.LlmSignalStrategy], docs/17 §17.8 R2).
 * Нужен, чтобы доказать, что таймаут приводит к HOLD, а не к входу по
 * детерминированному fallback'у, и чтобы измерить, сколько сигналов теряется.
 *
 * Решение **детерминировано по (ticker, индекс бара)** — без random и без seed:
 * повторный прогон того же тикера на той же истории даёт ту же карту таймаутов,
 * поэтому WFA-фолды остаются сопоставимыми, а изменение `rate` двигает порог,
 * а не перетасовывает выборку. По той же причине в хеш не входит `cycleId` —
 * он меняется между прогонами.
 *
 * @property rate доля сэмплов (0.0…1.0), в которых таймаут имитируется; 0.0 — выключено.
 * @property enabled признак, что инъекция вообще сконфигурирована.
 */
class LlmTimeoutInjection private constructor(
    val rate: Double,
) {
    init {
        require(rate in 0.0..1.0) {
            "bt.agent.timeout-injection-rate must be in 0.0..1.0, got $rate"
        }
    }

    val enabled: Boolean
        get() = rate > 0.0

    /**
     * Таймаут для данного сэмпла? Детерминированно по (ticker, index).
     *
     * Хеш — 64-битное перемешивание (splitmix64-подобное) от hash(ticker) и
     * индекса; сравнение идёт по [Double] представлению старших 53 бит, поэтому
     * `rate = 1.0` срабатывает всегда, а `0.0` — никогда, без ветвлений.
     */
    fun shouldTimeout(
        ticker: String,
        index: Int,
    ): Boolean {
        if (rate == 0.0) return false
        if (rate == 1.0) return true
        val mixed = mix(hash(ticker) xor (index.toLong() * GOLDEN))
        return unitInterval(mixed) < rate
    }

    /** Стабильный 64-битный hash строки (FNV-1a). */
    private fun hash(value: String): Long {
        var acc = FNV_OFFSET
        for (ch in value) {
            acc = acc xor ch.code.toLong()
            acc *= FNV_PRIME
        }
        return acc
    }

    /**
     * Старшие 53 бита хеша в [0.0; 1.0). Сдвиг вправо на [SHIFT] оставляет ровно
     * 53 значащих бита, поэтому делитель = 2^(64 - SHIFT).
     */
    private fun unitInterval(value: Long): Double = (value ushr SHIFT).toDouble() / 1L.shl(Long.SIZE_BITS - SHIFT).toDouble()

    companion object {
        /** Выключено (поведение прогона не меняется). */
        val DISABLED = LlmTimeoutInjection(0.0)

        /**
         * Фабрика из значения конфига. Отрицательное значение — опечатка конфига и
         * поднимает ошибку, а не молча выключает инъекцию; выключает только ровно 0.0.
         */
        fun from(rate: Double): LlmTimeoutInjection {
            require(rate >= 0.0 && rate <= 1.0) {
                "bt.agent.timeout-injection-rate must be in 0.0..1.0, got $rate"
            }
            return if (rate == 0.0) DISABLED else LlmTimeoutInjection(rate)
        }

        private const val FNV_OFFSET = -3750763034362895579L // 14695981039346656037 в знаковом виде
        private const val FNV_PRIME = 1099511628211L
        private const val GOLDEN = -7046029254386353131L // 0x9E3779B97F4A7C15
        private const val SHIFT = 11
    }
}

/** Старшие 53 бита после splitmix-подобного перемешивания (день/бары не «слипаются»). */
private fun mix(value: Long): Long {
    var z = value + -7046029254386353131L
    z = (z xor (z ushr 30)) * -4658895280553007687L
    z = (z xor (z ushr 27)) * -7723592293110705685L
    return z xor (z ushr 31)
}
