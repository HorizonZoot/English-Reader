package io.github.zoot.englishreader.viewmodel

import io.github.zoot.englishreader.data.remote.ai.AiEndpointResolver

internal data class AiProfileValidation(
    val nameRequired: Boolean,
    val endpointInvalid: Boolean,
    val modelRequired: Boolean,
    val keyRequired: Boolean,
    val temperatureInvalid: Boolean
) {
    val valid: Boolean get() = !nameRequired && !endpointInvalid && !modelRequired &&
        !keyRequired && !temperatureInvalid

    companion object {
        fun validateDiscovery(
            baseUrl: String,
            apiKey: String,
            canUseSavedKey: Boolean
        ): AiProfileValidation = validate(
            name = "", baseUrl = baseUrl, modelId = "", apiKey = apiKey,
            temperature = "", canKeepSavedKey = canUseSavedKey
        ).copy(nameRequired = false, modelRequired = false, temperatureInvalid = false)

        fun validate(
            name: String,
            baseUrl: String,
            modelId: String,
            apiKey: String,
            temperature: String,
            canKeepSavedKey: Boolean
        ): AiProfileValidation {
            val endpointValid = try {
                AiEndpointResolver.chatCompletionsUrl(baseUrl)
                true
            } catch (_: IllegalArgumentException) {
                false
            }
            val parsedTemperature = temperature.trim().toDoubleOrNull()
            return AiProfileValidation(
                nameRequired = name.isBlank(),
                endpointInvalid = !endpointValid,
                modelRequired = modelId.isBlank(),
                keyRequired = apiKey.isBlank() && !canKeepSavedKey,
                temperatureInvalid = parsedTemperature == null || !parsedTemperature.isFinite() ||
                    parsedTemperature !in 0.0..2.0
            )
        }
    }
}
