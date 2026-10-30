package com.trading.bot.backtest

import com.trading.bot.config.BacktestConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.reflect.KParameter
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

/**
 * Связка research-оверрайдов фильтров входа: `EntryFilterOverrides.anyProvided` →
 * `EntryFilters.from(config, overrides)`.
 *
 * Раньше проверялись только session-поля (5 из 60 elvis-ветвей). Здесь связка
 * проверяется рефлексией **по всем полям сразу**: любое новое поле, добавленное в
 * [EntryFilterOverrides] или [EntryFilters] без проводки в `from`, роняет тест —
 * это ровно тот дефект, который в исследованиях выглядел как «фильтр не работает»
 * (query-параметр молча игнорировался, прогон совпадал с baseline).
 */
class EntryFiltersOverrideWiringTest {
    private val overrideCtor =
        EntryFilterOverrides::class.primaryConstructor
            ?: error("EntryFilterOverrides must be a data class with a primary constructor")

    private val overrideParams: List<KParameter> = overrideCtor.parameters.filter { it.name != null }

    private val configProps =
        run {
            val props = BacktestConfig::class.memberProperties
            props.forEach { it.isAccessible = true }
            props.associateBy { it.name }
        }

    private val filtersProps =
        run {
            val props = EntryFilters::class.memberProperties
            props.forEach { it.isAccessible = true }
            val withoutCompanion = props.filterNot { it.name == "Companion" }
            withoutCompanion.associateBy { it.name }
        }

    /**
     * Единственные поля, где имя оверрайда/конфига не совпадает с именем поля фильтра
     * (`sessionFilterX` → `sessionX`, `pullbackFilterEnabled` → `pullbackEnabled`).
     */
    private val overrideToFilter =
        mapOf(
            "sessionFilterEnabled" to "sessionEnabled",
            "sessionFilterStartMinutes" to "sessionStartMinutes",
            "sessionFilterEndMinutes" to "sessionEndMinutes",
            "pullbackFilterEnabled" to "pullbackEnabled",
        )

    private val filterToOverride = overrideToFilter.entries.associate { (override, filter) -> filter to override }

    /** Значение, гарантированно отличное от конфигурационного (чтобы оверрайд был доказан). */
    private fun differentValue(
        name: String,
        current: Any?,
    ): Any =
        when (current) {
            is Boolean -> !current
            is Int -> current + 1
            is Double -> current + 0.5
            is String -> "${current}_OVERRIDE"
            else -> error("unsupported type of $name: ${current?.let { it::class }}")
        }

    private fun configValue(name: String): Any? {
        val prop = configProps[name] ?: error("BacktestConfig has no property '$name'")
        return prop.getter.call(BacktestConfig())
    }

    /** Оверрайды: только перечисленные параметры, значения отличаются от конфига. */
    private fun overridesFor(vararg only: String): EntryFilterOverrides {
        val names = if (only.isEmpty()) emptySet() else only.toSet()
        val args =
            overrideParams
                .filter { it.name in names }
                .associateWith { param -> differentValue(param.name!!, configValue(param.name!!)) }
        return overrideCtor.callBy(args)
    }

    @Test
    fun `anyProvided is false only when nothing is provided`() {
        assertFalse(EntryFilterOverrides().anyProvided, "пустой набор оверрайдов не должен ничего включать")
        assertTrue(overridesFor(*overrideParams.mapNotNull { it.name }.toTypedArray()).anyProvided)
    }

    @Test
    fun `every single override field marks anyProvided as true`() {
        // Ветвь `||` в anyProvided короткозамкнутая: чтобы покрыть каждое поле,
        // оно должно проверяться как ЕДИНСТВЕННЫЙ заданный оверрайд.
        overrideParams.forEach { param ->
            val name = param.name!!
            assertTrue(
                overridesFor(name).anyProvided,
                "оверрайд '$name' не приводит к anyProvided=true — поле потеряно в OR-цепочке",
            )
        }
    }

    @Test
    fun `config values are used when no overrides are given`() {
        val config = BacktestConfig()
        val filters = EntryFilters.from(config)

        val configPropsChecked = mutableSetOf<String>()
        filtersProps.forEach { (name, prop) ->
            val configName = filterToOverride[name] ?: name
            val cfg = configProps[configName] ?: return@forEach
            assertEquals(cfg.getter.call(config), prop.getter.call(filters), "'$name' должен браться из bt-конфига")
            configPropsChecked += name
        }
        assertTrue(
            configPropsChecked.size >= 55,
            "проверено только ${configPropsChecked.size} полей — похоже, поле фильтра не проведено из BacktestConfig",
        )
    }

    @Test
    fun `query overrides win over config for every field`() {
        val config = BacktestConfig()
        val overrides = overridesFor(*overrideParams.mapNotNull { it.name }.toTypedArray())
        val filters = EntryFilters.from(config, overrides)

        overrideParams.forEach { param ->
            val name = param.name!!
            val expected = overrideCtor.callBy(mapOf(param to differentValue(name, configValue(name))))
            val expectedValue = overrideGetter(expected, name)
            val filterName = overrideToFilter[name] ?: name
            val actual = filtersProps[filterName]?.getter?.call(filters)
            assertEquals(expectedValue, actual, "оверрайд '$name' не дошёл до EntryFilters — фильтр откалибровать нельзя")
        }
    }

    private fun overrideGetter(
        overrides: EntryFilterOverrides,
        name: String,
    ): Any? {
        val prop =
            EntryFilterOverrides::class.memberProperties.firstOrNull { it.name == name }
                ?: error("no property $name")
        prop.isAccessible = true
        return prop.getter.call(overrides)
    }
}
