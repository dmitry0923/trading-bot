package com.trading.bot.agent
import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.client.LlmClient
import com.trading.bot.model.*
import com.trading.bot.repository.AgentLogRepository
import kotlinx.coroutines.coroutineScope
import mu.KotlinLogging
import org.springframework.stereotype.Component
import kotlin.math.abs
import kotlin.math.sqrt

@Component
class TechnicalAnalysisAgent(private val llmClient: LlmClient, private val agentLogRepository: AgentLogRepository, private val objectMapper: ObjectMapper) {
    private val logger = KotlinLogging.logger {}
    suspend fun analyze(ticker: String, candles: List<Candle>, snapshot: MarketSnapshot, cycleId: String): TechnicalReport = coroutineScope {
        val start = System.currentTimeMillis()
        val ind = calcInd(candles)
        val prompt = "Акция $ticker на ММВБ. Цена: ${snapshot.currentPrice}. Свечи:
" + candles.takeLast(15).joinToString("
") { "${it.time}: O=${it.open} H=${it.high} L=${it.low} C=${it.close} V=${it.volume}" } + "
Индикаторы: ${ind.entries.joinToString(", ") { "${it.key}=${String.format("%.3f", it.value)}" }}
Дай JSON: trend, rsi, macd, macdSignal, bbPosition, ema50, ema200, atr, volumeProfile, supportLevels[], resistanceLevels[], pattern, conclusion(BULLISH/BEARISH/NEUTRAL), confidence(0-1), reasoning(русский)"
        val sys = "Ты — эксперт теханализа ММВБ. Ответь ТОЛЬКО JSON."
        val resp = llmClient.chat(sys, prompt)
        val report = parseTech(resp.content, ind)
        agentLogRepository.save(AgentLog(cycleId = cycleId, agentName = "Agent-1-Technical", ticker = ticker, action = report.conclusion, confidence = report.confidence, reasoning = report.reasoning, rawOutput = resp.content, latencyMs = System.currentTimeMillis() - start))
        report.copy(rawOutput = resp.content)
    }
    private fun calcInd(c: List<Candle>): Map<String, Double> {
        if (c.size < 30) return emptyMap(); val cl = c.map { it.close.toDouble() }
        fun ema(d: List<Double>, p: Int): List<Double> { val m = 2.0 / (p + 1); val o = mutableListOf(d.take(p).average()); for (i in p until d.size) o.add((d[i] - o.last()) * m + o.last()); return o }
        fun rsi(d: List<Double>, p: Int = 14): Double { var g = 0.0; var l = 0.0; for (i in 1..p) { val diff = d[i] - d[i - 1]; if (diff > 0) g += diff else l += abs(diff) }; var ag = g / p; var al = l / p; for (i in p + 1 until d.size) { val diff = d[i] - d[i - 1]; ag = (ag * (p - 1) + maxOf(0.0, diff)) / p; al = (al * (p - 1) + maxOf(0.0, -diff)) / p }; return if (al == 0.0) 100.0 else 100.0 - (100.0 / (1 + ag / al)) }
        fun atr(cd: List<Candle>, p: Int = 14): Double { val tr = cd.zipWithNext { prev, cur -> maxOf(cur.high.toDouble() - cur.low.toDouble(), maxOf(abs(cur.high.toDouble() - prev.close.toDouble()), abs(cur.low.toDouble() - prev.close.toDouble()))) }; return tr.takeLast(p).average() }
        fun bb(d: List<Double>, p: Int = 20): Double { val s = d.takeLast(p); val a = s.average(); val std = sqrt(s.map { (it - a) * (it - a) }.average()); val cur = s.last(); return if (std == 0.0) 0.5 else ((cur - (a - 2 * std)) / (4 * std)).coerceIn(0.0, 1.0) }
        val e12 = ema(cl, 12).lastOrNull() ?: 0.0; val e26 = ema(cl, 26).lastOrNull() ?: 0.0
        return mapOf("sma20" to cl.takeLast(20).average(), "sma50" to cl.takeLast(50).average(), "ema12" to e12, "ema26" to e26, "macd" to (e12 - e26), "rsi" to rsi(cl), "atr" to atr(c), "bbPosition" to bb(cl))
    }
    private fun parseTech(content: String, ind: Map<String, Double>): TechnicalReport {
        return try { val j = objectMapper.readTree(content.replace("```json", "").replace("```", "").trim())
            TechnicalReport(j["trend"]?.asText() ?: "NEUTRAL", j["rsi"]?.asDouble() ?: ind["rsi"] ?: 50.0, j["macd"]?.asDouble() ?: ind["macd"] ?: 0.0, j["macdSignal"]?.asDouble() ?: ind["macd"] ?: 0.0, j["bbPosition"]?.asDouble() ?: ind["bbPosition"] ?: 0.5, j["ema50"]?.asDouble() ?: ind["sma50"] ?: 0.0, j["ema200"]?.asDouble() ?: 0.0, j["atr"]?.asDouble() ?: ind["atr"] ?: 0.0, j["volumeProfile"]?.asText() ?: "NEUTRAL", j["supportLevels"]?.map { it.asDouble() } ?: emptyList(), j["resistanceLevels"]?.map { it.asDouble() } ?: emptyList(), j["pattern"]?.asText(), j["conclusion"]?.asText() ?: "NEUTRAL", j["confidence"]?.asDouble() ?: 0.0, j["reasoning"]?.asText() ?: "", content)
        } catch (e: Exception) { logger.error(e) { "Tech parse error" }; TechnicalReport("NEUTRAL", 50.0, 0.0, 0.0, 0.5, 0.0, 0.0, 0.0, "NEUTRAL", emptyList(), emptyList(), null, "NEUTRAL", 0.0, "Parse error", content) }
    }
}
