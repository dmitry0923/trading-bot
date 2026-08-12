package com.trading.bot.client

import kotlinx.coroutines.flow.Flow

/**
 * Низкоуровневое WebSocket-соединение канала ордеров.
 *
 * Абстракция изолирует [WsOrderTransport] от транспортной библиотеки
 * (ReactorNetty), делая логику команда/ответ полностью юнит-тестируемой
 * на фейковом сокете.
 */
interface WsOrderSocketConnection {
    /** Входящие текстовые сообщения. Поток завершается при закрытии сокета. */
    val messages: Flow<String>

    /** Отправка текстового сообщения. Бросает исключение, если сокет закрыт. */
    suspend fun send(text: String)

    /** Закрытие соединения. */
    suspend fun close()
}

/**
 * Фабрика соединений канала ордеров (стратегия: реальная [ReactorWsOrderSocketFactory]
 * в проде, фейковая в тестах).
 */
interface WsOrderSocketFactory {
    /**
     * Открывает соединение и блокирует до установления (или бросает исключение).
     */
    suspend fun open(): WsOrderSocketConnection
}
