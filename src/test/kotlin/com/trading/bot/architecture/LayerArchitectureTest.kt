package com.trading.bot.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * Архитектурные guard-тесты (правила чистого домена).
 *
 * Правило 1: domain ничего не знает (Spring/Micrometer/repository/config/client/infrastructure).
 * Правило 2: агенты (стратегия) не несут риск-параметров (quantity/SL/TP/trailing/leverage).
 * Правило 3: риск-сервисы не исполняют сделки и не пишут в БД (никаких ордеров/событий).
 */
@AnalyzeClasses(
    packages = ["com.trading.bot"],
    importOptions = [ImportOption.DoNotIncludeTests::class],
)
@Suppress("ktlint:standard:property-naming", "unused")
class LayerArchitectureTest {
    /** Правило 1: чистый домен. domain зависит только от model, java и kotlin. */
    @ArchTest
    val `domain must not depend on infrastructure` =
        noClasses()
            .that()
            .resideInAnyPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "io.micrometer..",
                "..repository..",
                "..config..",
                "..client..",
                "..infrastructure..",
                "..service..",
            )

    /** Правило 1: в domain не должно быть Spring-компонентов. */
    @ArchTest
    val `domain must not use spring stereotypes` =
        noClasses()
            .that()
            .resideInAnyPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Service")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Component")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameStartingWith("Repository")

    /** Правило 2: агенты не декларируют риск-параметры сделки. */
    @ArchTest
    val `agents must not declare position sizing fields` =
        fields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("..agent..")
            .and()
            .haveNameMatching("quantity|stopLoss|takeProfit|trailingStop|leverage|margin")
            .should()
            .containNumberOfElements(DescribedPredicate.equalTo(0))
            .allowEmptyShould(true)

    /** Правило 2: агенты не знают о риск-сервисах.
     *  (Контекстные сервисы — IndicatorCalculator/MacroContextService/TradeAnalysisService/
     *  RedisCacheService — разрешены: это данные, а не риск.) */
    @ArchTest
    val `agents must not depend on risk services` =
        noClasses()
            .that()
            .resideInAnyPackage("..agent..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..domain.risk..",
                "..application.risk..",
                "..service.RiskManagementService",
                "..service.DrawdownProtectionService",
                "..service.VolatilityIndexService",
                "..service.AdaptiveRiskService",
            )

    /** Правило 3: риск-сервисы не рассчитывают стопы и не управляют trailing. */
    @ArchTest
    val `risk services must not compute sl tp trailing` =
        methods()
            .that()
            .areDeclaredInClassesThat()
            .haveSimpleName("RiskManagementService")
            .and()
            .haveNameMatching("calcSL|calcTP|shouldCloseBy.*|updateTrailingStop")
            .should()
            .containNumberOfElements(DescribedPredicate.equalTo(0))
            .allowEmptyShould(true)

    /** Правило 3: риск-движок фьючерсов отвечает только Да/Нет (нет сайзинга и стопов). */
    @ArchTest
    val `futures risk engine must not compute order params` =
        methods()
            .that()
            .areDeclaredInClassesThat()
            .haveSimpleName("FuturesRiskEngine")
            .and()
            .haveName("validateEntry")
            .should()
            .containNumberOfElements(DescribedPredicate.equalTo(0))
            .allowEmptyShould(true)

    /** Правило 3: риск не исполняет сделки и не публикует события. */
    @ArchTest
    val `risk must not execute orders or publish events` =
        noClasses()
            .that()
            .resideInAnyPackage("..domain.risk..", "..service.RiskManagementService")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..application.OrderExecutionEngine",
                "..service.OrderOutboxService",
                "..event.TradingEventPublisher",
                "..client.AlorClient",
                "..client.AlorWebSocketClient",
            )

    /** Правило 3: риск-движок фьючерсов не читает БД напрямую. */
    @ArchTest
    val `futures risk engine must not depend on position repository` =
        noClasses()
            .that()
            .haveSimpleName("FuturesRiskEngine")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..repository..")

    /** Стратегия (сущность-решение) больше не несёт размер/стопы — только в историю.
     *  Ограничено доменным Signal (внутренний Guardrails$Signal с таким же simple name не в счёт). */
    @ArchTest
    val `signal must not carry sizing or stops` =
        classes()
            .that()
            .resideInAnyPackage("..domain.signal..")
            .and()
            .haveSimpleName("Signal")
            .should()
            .onlyHaveDependentClassesThat()
            .resideInAnyPackage("..domain..", "..application..", "..service..", "..event..", "..model..", "..agent..")

    /** Стратегии (детерминированные правила + LLM-путь) не считают риск-параметры
     *  и не исполняют сделки: сайзинг/стопы — этап RiskEngine/PositionSizer/OrderBuilder. */
    @ArchTest
    val `strategies must not depend on risk or execution layer` =
        noClasses()
            .that()
            .resideInAnyPackage("..application.strategy..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..domain.risk..",
                "..application.risk..",
                "..application.OrderExecutionEngine",
                "..service.OrderOutboxService",
                "..event.TradingEventPublisher",
            )

    /** DecisionEngine — оркестратор входа: домен не знает о нём. */
    @ArchTest
    val `domain must not depend on decision layer` =
        noClasses()
            .that()
            .resideInAnyPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application.decision..")

    /** DecisionEngine — оркестратор входа: риск-слой не знает о нём. */
    @ArchTest
    val `risk must not depend on decision layer` =
        noClasses()
            .that()
            .resideInAnyPackage("..application.risk..", "..domain.risk..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application.decision..")

    /** DecisionEngine — оркестратор входа: стратегии не знают о нём. */
    @ArchTest
    val `strategies must not depend on decision layer` =
        noClasses()
            .that()
            .resideInAnyPackage("..application.strategy..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application.decision..")
}
