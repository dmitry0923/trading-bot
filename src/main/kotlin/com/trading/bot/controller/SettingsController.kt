package com.trading.bot.controller

import com.trading.bot.service.SettingsService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/settings")
@CrossOrigin(origins = ["*"])
class SettingsController(private val settingsService: SettingsService) {

    @GetMapping
    fun get() = settingsService.getSettings()

    @PostMapping
    fun update(@RequestBody settings: com.trading.bot.model.BotSettings) = settingsService.updateSettings(settings)
}
