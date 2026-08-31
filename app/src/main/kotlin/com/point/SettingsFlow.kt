package com.point

import com.point.core.flow.AiKeyCheck
import com.point.core.flow.AiFacts
import com.point.core.flow.BuiltInAiKeys
import com.point.core.flow.KeyProbe
import com.point.core.flow.KeyVerdict
import com.point.core.flow.SensorySettings
import com.point.core.flow.UserAiConfig
import com.point.core.flow.UserAiKey
import com.point.core.flow.UserAiKeys
import com.point.core.flow.UserKeyStore
import com.point.core.flow.aiCall
import com.point.core.flow.aiCheckedLine
import com.point.core.flow.aiOutcomeOfStatus
import com.point.core.flow.aiServiceLines

/**
 * Всё, чем живут настройки и ключи: хранилища, справочник и проверка (#833, шаг 3).
 *
 * Пять зависимостей ядру поодиночке не нужны — они нужны одной теме и ходят вместе.
 * Внедряются они по-прежнему поштучно, здесь же собираются в одно имя.
 */
class SettingsParts @javax.inject.Inject constructor(
    val userKeys: UserKeyStore,
    val aiFacts: AiFacts,
    val builtInKeys: BuiltInAiKeys,
    val aiKeyCheck: AiKeyCheck,
    val sensorySettings: SensorySettings,
)

/**
 * Настройки и ключи живут своим держателем (#833, шаг 3).
 *
 * Третий шаг разреза `FlowViewModel` — после разговора (`ChatFlow`) и аккаунта
 * (`AccountFlow`). Держатель знает, какие есть сервисы, чей ключ работает и что об этом
 * известно; экранное состояние остаётся у ядра — оно и владеет экраном.
 *
 * Проверка ключа делается только по тапу человека: фоном Point не проверяет ничего (#699).
 * Каждая проверка оставляет факт о сервисе — по нему человек видит, что было в последний
 * раз, не нажимая заново.
 */
class SettingsFlow(private val parts: SettingsParts) {

    /** Ключи человека — как они лежат сейчас. */
    fun keys(): UserAiKeys = runCatching { parts.userKeys.keys() }.getOrDefault(UserAiKeys.NONE)

    /** Есть ли хоть один свой ключ: по этому вопросу экран решает, звать ли за ключом. */
    fun anyKeyOfMine(): Boolean = keys().mine.isNotEmpty()

    /**
     * Все известные сервисы списком, в том порядке, в каком Point к ним обращается: ключ,
     * что умеет и последний факт (#699).
     */
    fun screen(now: Long = System.currentTimeMillis()): AiKeysScreen {
        val keys = keys()
        val facts = runCatching { parts.aiFacts.all() }.getOrDefault(emptyMap())
        val ours = runCatching { parts.builtInKeys.have() }.getOrDefault(emptySet())
        return AiKeysScreen(
            keys = keys,
            services = aiServiceLines(keys, ours, facts, now),
            checkedLine = aiCheckedLine(facts, now),
        )
    }

    /** Каким сервисом человек пользуется: первым своим ключом, если он есть. */
    fun chosenService(): String? = runCatching {
        val mine = keys().mine.firstOrNull() ?: return@runCatching null
        com.point.core.flow.AI_PROVIDERS.firstOrNull { it.id == mine.providerId }?.name
    }.getOrNull()

    /** Одна дешёвая проверка одного сервиса — только по тапу человека (#699). */
    suspend fun check(key: UserAiKey): KeyVerdict {
        val verdict = probe(key.providerId, aiCall(key))
        if (verdict is KeyVerdict.Works) save(key)
        return verdict
    }

    /**
     * «Проверить все» — по одному дешёвому запросу к каждому сервису, для которого есть
     * ключ. [afterEach] зовётся после каждого, чтобы экран не ждал конца всего обхода.
     */
    suspend fun checkAll(afterEach: () -> Unit) {
        val keys = keys()
        for (provider in com.point.core.flow.AI_PROVIDERS) {
            val mine = keys.of(provider.id)
            val key = mine?.apiKey ?: runCatching { parts.builtInKeys.key(provider.id) }.getOrDefault("")
            if (key.isBlank()) continue
            val call = mine?.let(::aiCall) ?: UserAiConfig(
                apiKey = key,
                baseUrl = provider.baseUrl,
                model = provider.models.substringBefore(','),
            )
            probe(provider.id, call)
            afterEach()
        }
        keys.of(com.point.core.flow.OWN_SERVICE_ID)?.let { probe(com.point.core.flow.OWN_SERVICE_ID, aiCall(it)) }
    }

    /**
     * Ключ, который человек только что вписал: отметка времени — сейчас.
     *
     * Отметка решает спор с другим устройством (#610), поэтому ставится она там, где событие
     * и произошло, — здесь.
     */
    suspend fun save(key: UserAiKey) {
        runCatching { parts.userKeys.save(key.copy(savedAt = System.currentTimeMillis())) }
    }

    /**
     * Ключ, приехавший со стороны, кладётся со своей отметкой (#610).
     *
     * Переставить её на «сейчас» значило бы объявить чужое своим и всегда выигрывать спор
     * настроек — устройство, которое всего лишь приняло ключ, выглядело бы тем, где его
     * меняли последним.
     */
    suspend fun accept(key: UserAiKey) {
        runCatching { parts.userKeys.save(key) }
    }

    suspend fun forget(providerId: String) {
        runCatching { parts.userKeys.forget(providerId) }
    }

    fun soundEnabled(): Boolean = runCatching { parts.sensorySettings.isSoundEnabled() }.getOrDefault(true)

    suspend fun setSound(enabled: Boolean) {
        runCatching { parts.sensorySettings.setSoundEnabled(enabled) }
    }

    /** Когда человек в последний раз менял ключи: по этому решается спор с другим устройством. */
    fun keysChangedAt(): Long = keys().mine.maxOfOrNull { it.savedAt } ?: 0L

    /** Ключ, приехавший с другого устройства, кладётся только если он отличается от своего. */
    suspend fun acceptFromAccount(arrived: UserAiKeys, mine: UserAiKeys) {
        arrived.mine
            .filter { key -> mine.of(key.providerId)?.apiKey != key.apiKey }
            .forEach { key -> accept(key) }
    }

    private suspend fun probe(providerId: String, call: UserAiConfig): KeyVerdict {
        val probe = runCatching { parts.aiKeyCheck.check(call) }
            .getOrElse { KeyProbe(error = com.point.core.flow.withoutKey(it.message.orEmpty(), call.apiKey)) }
        runCatching { parts.aiFacts.remember(providerId, aiOutcomeOfStatus(probe.status)) }
        return com.point.core.flow.keyVerdict(probe)
    }
}
