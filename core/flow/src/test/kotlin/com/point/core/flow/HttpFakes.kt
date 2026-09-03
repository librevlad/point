package com.point.core.flow

/** Что ушло сервису формой: проверки читают адрес, заголовки и поля, а не сеть. */
class SentForm(val url: String, val headers: Map<String, String>, val parts: List<FormPart>) {
    fun field(name: String): String? =
        parts.filterIsInstance<FormPart.Field>().firstOrNull { it.name == name }?.value

    fun file(name: String): FormPart.Binary? =
        parts.filterIsInstance<FormPart.Binary>().firstOrNull { it.name == name }
}

/** Сеть за швом [HttpFiles], подменённая заранее известным ответом. */
class FakeHttpFiles(
    private val onPost: (SentForm) -> HttpResult = { HttpResult(200, "[]") },
) : HttpFiles {

    val posts = mutableListOf<SentForm>()

    override suspend fun postMultipart(
        url: String,
        headers: Map<String, String>,
        parts: List<FormPart>,
    ): HttpResult = SentForm(url, headers, parts).let {
        posts += it
        onPost(it)
    }
}
