package com.point.core.flow

import com.point.core.model.Feature
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * У прочитанного снимка бумаги «Скан» — первое превращение (#1044, замер 23.10.2026): на
 * корпусе владельца он прятался за «Показать ещё», под «Улучшить фото» и «Убрать фон».
 */
class ScanLeadsReadPageTest {

    private val makes = listOf(EnhancePhotoCapability(), ScanCapability(), CleanMetadataCapability())

    private fun order(state: ObjectState) = makes.sortedWith(byIntentThenPriority(state, intent = null)).map { it.id }

    @Test
    fun `снимок с текстом — скан впереди улучшения`() {
        val read = order(ObjectState(ObjectKind.IMAGE, features = setOf(Feature.HAS_TEXT)))
        assertTrue(read.toString(), read.indexOf(ScanCapability.ID) < read.indexOf(EnhancePhotoCapability.ID))
    }

    @Test
    fun `снимок без текста — скан на своём обычном месте`() {
        val photo = order(ObjectState(ObjectKind.IMAGE))
        assertTrue(photo.toString(), photo.indexOf(ScanCapability.ID) > photo.indexOf(EnhancePhotoCapability.ID))
    }
}
