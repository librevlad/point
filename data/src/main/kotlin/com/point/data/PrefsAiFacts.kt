package com.point.data

import android.content.Context
import androidx.core.content.edit
import com.point.core.flow.AiFact
import com.point.core.flow.AiFacts
import com.point.core.flow.AiOutcome
import com.point.core.flow.decodeAiFacts
import com.point.core.flow.encodeAiFacts
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Последний исход обращения к каждому сервису — на устройстве и через
 * перезапуск (#699). Фоном ничего не проверяется: сюда пишут только настоящие
 * обращения и проверка по тапу человека.
 */
@Singleton
class PrefsAiFacts @Inject constructor(
    @ApplicationContext context: Context,
) : AiFacts {

    private val prefs = context.getSharedPreferences("point_ai_facts", Context.MODE_PRIVATE)

    override fun all(): Map<String, AiFact> = decodeAiFacts(prefs.getString(FACTS, null))

    override fun remember(providerId: String, outcome: AiOutcome) {
        if (providerId.isBlank()) return
        val next = all() + (providerId to AiFact(outcome, System.currentTimeMillis()))
        prefs.edit { putString(FACTS, encodeAiFacts(next)) }
    }

    private companion object {
        const val FACTS = "facts"
    }
}
