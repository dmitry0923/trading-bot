package com.trading.bot.infrastructure.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * Шаблон промпта из Prompt Registry.
 * Хранит system-промпт и user-шаблон с Jinja2-подобным синтаксисом {{variable}}.
 */
data class PromptTemplate(
    val name: String,
    val version: String,
    val system: String,
    val userTemplate: String
) {
    /**
     * Рендерит user-шаблон, подставляя значения переменных.
     * Отсутствующие переменные заменяются на пустую строку.
     */
    fun renderUser(variables: Map<String, Any>): String = render(userTemplate, variables)

    /**
     * Рендерит system-промпт, подставляя значения переменных (если есть {{...}}).
     */
    fun renderSystem(variables: Map<String, Any>): String = render(system, variables)

    private fun render(template: String, variables: Map<String, Any>): String {
        val compiled = COMPILED.getOrPut(this to template) { compile(template) }
        return StringBuilder(template.length).also { out ->
            for (part in compiled) {
                when (part) {
                    is TemplatePart.Literal -> out.append(part.text)
                    is TemplatePart.Variable -> out.append(variables[part.name]?.toString() ?: "")
                }
            }
        }.toString()
    }

    private sealed interface TemplatePart {
        data class Literal(val text: String) : TemplatePart
        data class Variable(val name: String) : TemplatePart
    }

    private fun compile(template: String): List<TemplatePart> {
        val parts = mutableListOf<TemplatePart>()
        val regex = Regex("""\{\{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\}\}""")
        var last = 0
        for (match in regex.findAll(template)) {
            if (match.range.first > last) {
                parts.add(TemplatePart.Literal(template.substring(last, match.range.first)))
            }
            parts.add(TemplatePart.Variable(match.groupValues[1]))
            last = match.range.last + 1
        }
        if (last < template.length) {
            parts.add(TemplatePart.Literal(template.substring(last)))
        }
        return parts
    }

    companion object {
        private val COMPILED = ConcurrentHashMap<Pair<PromptTemplate, String>, List<TemplatePart>>()
    }
}
