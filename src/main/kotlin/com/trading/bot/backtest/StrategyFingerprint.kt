package com.trading.bot.backtest

import java.security.MessageDigest

/**
 * Фрintprиnt production-стратегии (SHA-256).
 *
 * Используется для жёсткой привязки per-ticker LIVE-одобрения к той стратегии,
 * которая реально прошла WFA/holdout/MC: если runtime-конфигурация (confidence,
 * леверидж, риск, SL/TP, версия стратегии и прочие параметры решения/сайзинга)
 * изменяется после approve — фрintprиnt меняется и исполнение блокируется
 * (fail-closed), пока не будет проведён новый deployment-цикл.
 *
 * SHA-256 вместо [String.hashCode]: [String.hashCode] — 32-битный JVM-хэш с
 * коллизиями и недостаточной диагностической ценностью для safety-critical
 * identity деплоя. Здесь — полный 64-символьный hex.
 */
object StrategyFingerprint {
    fun sha256(content: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
