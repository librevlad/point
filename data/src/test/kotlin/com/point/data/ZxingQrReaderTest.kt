package com.point.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт QrReader: `null` — «посмотрели, кода нет»; нечитаемое изображение — исключение.
 */
class ZxingQrReaderTest {

    @Test
    fun `an image that cannot be opened is an exception, not a quiet null`() = runTest {
        val outcome = runCatching { ZxingQrReader().decode("/nowhere/broken.png") }

        assertTrue("нечитаемый файл обязан быть ошибкой чтения, а не «кода нет»", outcome.isFailure)
    }
}
