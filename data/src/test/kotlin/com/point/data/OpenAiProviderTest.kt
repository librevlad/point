package com.point.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The provider-chain filtering that makes "all free providers, max" self-activating. */
class OpenAiProviderTest {

    private fun provider(label: String, key: String) =
        OpenAiProvider(label, "https://x/v1", key, "model")

    @Test
    fun `configured keeps only keyed providers, order preserved`() {
        val all = listOf(
            provider("openrouter", "sk-or-1"),
            provider("groq", ""), // not signed up yet -> skipped
            provider("mistral", "sk-mi-2"),
            provider("cerebras", "  "), // blank -> skipped
        )
        assertEquals(listOf("openrouter", "mistral"), all.configured().map { it.label })
    }

    @Test
    fun `configured is empty when no keys are set`() {
        val all = listOf(provider("openrouter", ""), provider("groq", ""))
        assertEquals(emptyList<OpenAiProvider>(), all.configured())
    }
}
