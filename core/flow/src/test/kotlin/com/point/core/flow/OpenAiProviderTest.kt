package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderTest {

    private fun provider(label: String, key: String) =
        OpenAiProvider(label, "https://x/v1", key, "model")

    @Test
    fun `openAiModels expands a comma list into one provider per model, same key`() {
        val list = openAiModels("openrouter", "https://u/v1", "sk-key", " a:free , b:free ,, c:free ")
        assertEquals(listOf("a:free", "b:free", "c:free"), list.map { it.model })
        assertTrue(list.all { it.label == "openrouter" && it.apiKey == "sk-key" && it.baseUrl == "https://u/v1" })
    }

    @Test
    fun `configured keeps only keyed providers, order preserved`() {
        val all = listOf(
            provider("openrouter", "sk-or-1"),
            provider("groq", ""),
            provider("mistral", "sk-mi-2"),
            provider("cerebras", "  "),
        )
        assertEquals(listOf("openrouter", "mistral"), all.configured().map { it.label })
    }

    @Test
    fun `configured is empty when no keys are set`() {
        val all = listOf(provider("openrouter", ""), provider("groq", ""))
        assertEquals(emptyList<OpenAiProvider>(), all.configured())
    }

    @Test
    fun `mistral small and medium count as vision - measured, not guessed by name`() {
        assertTrue(isVisionModel("mistral-medium-latest"))
        assertTrue(isVisionModel("mistral-small-latest"))
        assertTrue(isVisionModel("pixtral-12b-2409"))
    }

    @Test
    fun `a text-only model still keeps photos out`() {
        assertFalse(isVisionModel("gpt-oss-120b"))
        assertFalse(isVisionModel("llama-3.3-70b-versatile"))
    }
}
