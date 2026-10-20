package com.point.core.flow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProvidersTest {

    @Test
    fun `у каждого провайдера есть куда пойти за ключом`() {
        AI_PROVIDERS.forEach { provider ->
            assertTrue(
                "${provider.name}: ссылка за ключом обязана быть настоящей",
                provider.keyUrl.startsWith("https://") && provider.keyUrl.length > 12,
            )
        }
    }

    @Test
    fun `у каждого есть адрес и модель — человеку их набирать не придётся`() {
        AI_PROVIDERS.forEach { provider ->
            assertTrue("${provider.name}: нет адреса", provider.baseUrl.startsWith("https://"))
            assertTrue("${provider.name}: нет модели", provider.models.isNotBlank())
            assertTrue("${provider.name}: нечего сказать о нём человеку", provider.what.isNotBlank())
        }
    }

    @Test
    fun `адрес ведёт в OpenAI-совместимую дверь, а не в корень домена`() {

        AI_PROVIDERS.forEach { provider ->
            val path = provider.baseUrl.removePrefix("https://").substringAfter('/', "")
            assertTrue(
                "${provider.name}: адрес «${provider.baseUrl}» — корень домена, а нужна дверь API",
                path.isNotBlank(),
            )
        }
    }

    @Test
    fun `имена не повторяются — иначе выбор не выбор`() {
        assertEquals(AI_PROVIDERS.size, AI_PROVIDERS.map { it.id }.toSet().size)
        assertEquals(AI_PROVIDERS.size, AI_PROVIDERS.map { it.baseUrl }.toSet().size)
    }

    @Test
    fun `первым идёт тот, с кого проще начать`() {

        assertEquals("openrouter", AI_PROVIDERS.first().id)
    }

    @Test
    fun `обещание бесплатности всегда с датой проверки`() {

        // Дата известна, но человеку на глаза не выходит (#575): список стареет, и знать это
        // нужно нам, а в подписи человеку остаётся цена, а не наша бухгалтерия.
        AI_PROVIDERS.filter { it.freeNote != null }.forEach { provider ->
            assertTrue("«${provider.freeNote}» — обещание без даты проверки", provider.checkedAt != null)
            assertTrue("дата проверки вышла человеку на глаза", "проверено" !in provider.freeNote!!)
        }
    }

    @Test
    fun `сохранённый адрес узнаётся — экран откроется на своём провайдере`() {
        val groq = AI_PROVIDERS.first { it.id == "groq" }
        assertEquals(groq, providerForBaseUrl(groq.baseUrl))
        assertEquals(groq, providerForBaseUrl(groq.baseUrl + "/"))
        assertNotNull(providerForBaseUrl(groq.baseUrl.uppercase()))
    }

    @Test
    fun `чужой адрес не выдаётся за знакомый`() {

        assertNull(providerForBaseUrl("https://мой-прокси.local/v1"))
        assertNull(providerForBaseUrl(""))
    }

    @Test
    fun `ключ Groq из настроек виден тому, кто спрашивает про Groq`() {
        val keys = UserAiKeys.NONE.with(UserAiKey(GROQ_PROVIDER_ID, " gsk-мой "))

        assertEquals("gsk-мой", keys.keyFor(GROQ_PROVIDER_ID))
    }

    @Test
    fun `ключ другого провайдера за ключ Groq не выдаётся`() {

        val keys = UserAiKeys.NONE.with(UserAiKey("openrouter", "sk-or-мой"))

        assertEquals("", keys.keyFor(GROQ_PROVIDER_ID))
        assertEquals("", UserAiKeys.NONE.keyFor(GROQ_PROVIDER_ID))
    }

    @Test
    fun `отказ, который чинится ключом, узнаётся по словам обоих поколений`() {
        assertTrue(refusalNeedsKey("Расшифровать некому: Whisper слушает по ключу Groq. $KEY_SETTINGS_CALL"))
        assertTrue(refusalNeedsKey("AI не настроен — задайте свой ключ"))

        assertTrue(refusalNeedsKey("задайте свой ключ — откройте «$SETTINGS_TITLE» на домашнем экране"))

        assertFalse(refusalNeedsKey("AI недоступен — нет подключения к интернету"))
        assertFalse(refusalNeedsKey("В записи не слышно речи"))
    }

    @Test
    fun `ключ узнаётся по началу, которое ставит сам сервис`() {
        assertEquals("openrouter", providerOfKey("sk-or-v1-0123456789abcdef")?.id)
        assertEquals("groq", providerOfKey(" gsk_0123456789abcdef ")?.id)
        assertEquals("gemini", providerOfKey("AIzaSyA0123456789abcdef")?.id)
        assertEquals("anthropic", providerOfKey("sk-ant-api03-0123456789")?.id)
        assertEquals("openai", providerOfKey("sk-proj-0123456789abcdef")?.id)
        assertEquals("nvidia", providerOfKey("nvapi-0123456789abcdef")?.id)
        assertEquals("cerebras", providerOfKey("csk-0123456789abcdef")?.id)
    }

    @Test
    fun `по голому sk- и ключу без приметы Point не гадает`() {
        assertNull(providerOfKey("sk-0123456789abcdef0123456789"))
        assertNull(providerOfKey("0123456789abcdef0123456789abcdef"))
        assertNull(providerOfKey(""))
        assertNull(providerOfKey(null))
    }

    @Test
    fun `приметы не спорят между сервисами`() {
        // Ключ, узнанный за двоих, ушёл бы на пробу чужому сервису — чужой секрет в чужих руках.
        AI_PROVIDERS.forEach { provider ->
            provider.keyPrefixes.forEach { prefix ->
                val owners = AI_PROVIDERS.filter { other ->
                    other.keyPrefixes.any { prefix.startsWith(it) || it.startsWith(prefix) }
                }
                assertEquals("примета «$prefix» общая у ${owners.map { it.name }}", listOf(provider.id), owners.map { it.id })
            }
        }
    }

    @Test
    fun `номер аккаунта Workers AI собирается в адрес и читается из него обратно`() {
        val cloudflare = AI_PROVIDERS.single { it.id == "cloudflare" }
        val url = cloudflare.urlWithAccount(" 0123abcd ")

        assertEquals("https://api.cloudflare.com/client/v4/accounts/0123abcd/ai/v1", url)
        assertEquals("0123abcd", cloudflare.accountIn(url))
        assertEquals("", cloudflare.accountIn("https://example.com/v1"))
        assertEquals("ключ с полным адресом ходит туда же", "$url/chat/completions", probeUrl(url))
    }

    @Test
    fun `за ключом Point посылает к бесплатному сервису, которому вещь по силам`() {
        val any = keyErrandService(UserAiKeys.NONE)
        assertNotNull(any?.freeNote)
        assertEquals("запись расшифровывают не все", "groq", keyErrandService(UserAiKeys.NONE, com.point.core.model.ObjectKind.AUDIO)?.id)
        assertTrue("снимок пошёл бы к модели без глаз", keyErrandService(UserAiKeys.NONE, com.point.core.model.ObjectKind.IMAGE)!!.sees)
        assertNull("номер аккаунта — лишний шаг для первого ключа", keyErrandService(UserAiKeys.NONE)?.account)
    }

    @Test
    fun `туда, где ключ уже есть, второй раз не посылают`() {
        val first = keyErrandService(UserAiKeys.NONE)!!
        val keys = UserAiKeys.NONE.with(UserAiKey(first.id, "мой-ключ-0123456789"))

        assertTrue(keyErrandService(keys)?.id != first.id)
    }

    @Test
    fun `режим «не учатся на моём» не посылает к тем, кто учится`() {
        val service = keyErrandService(UserAiKeys.NONE, level = PrivacyLevel.NO_TRAINING)

        assertEquals(ReaderPromise.NO_TRAINING, service?.promise)
        assertNull("всё на устройстве — посылать некуда", keyErrandService(UserAiKeys.NONE, level = PrivacyLevel.DEVICE_ONLY))
    }
}
