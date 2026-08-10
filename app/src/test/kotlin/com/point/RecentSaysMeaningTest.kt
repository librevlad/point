package com.point

import com.point.core.flow.META_SEMANTIC_SUMMARY
import com.point.core.flow.META_SEMANTIC_TYPE
import com.point.core.model.Feature
import com.point.core.model.HistoryEntry
import com.point.core.model.ObjectKind
import com.point.core.model.ScratchRef
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Карточка «Недавнего» называет смысл, а не файл (#639): человек ищет глазами «Покупку»,
 * а не «probe.jpg». Знание для этого уже лежит в истории — объект переезжает туда целиком (#687).
 */
class RecentSaysMeaningTest {

    private fun entry(
        name: String?,
        kind: ObjectKind = ObjectKind.IMAGE,
        features: Set<Feature> = emptySet(),
        metadata: Map<String, String> = emptyMap(),
    ) = HistoryEntry(
        id = "id",
        mime = "image/jpeg",
        kind = kind,
        name = name,
        epochMillis = 0,
        ref = ScratchRef("/scratch/объект"),
        features = features,
        metadata = metadata,
    )

    @Test fun `понятый объект назван смыслом`() {
        val purchase = entry("probe.jpg", features = setOf(Feature.IS_PURCHASE))

        assertEquals("Покупка", entryTitle(purchase))
    }

    @Test fun `документ назван своим родом, а не именем файла`() {
        // Род берётся из того же словаря, что и на экране объекта: своего второго не заводим.
        val parcel = entry("scan_0012.pdf", kind = ObjectKind.PDF, metadata = mapOf(META_SEMANTIC_TYPE to "parcel"))

        assertEquals("Посылка", entryTitle(parcel))
    }

    @Test fun `визитка узнаётся по признаку, а не по имени`() {
        assertEquals("Визитка", entryTitle(entry("IMG_0912.jpg", features = setOf(Feature.HAS_VCARD))))
    }

    @Test fun `без знания карточка называется как раньше — именем файла`() {
        assertEquals("probe.jpg", entryTitle(entry("probe.jpg")))
    }

    @Test fun `нет ни знания, ни имени — остаётся вид объекта`() {
        assertEquals("Изображение", entryTitle(entry(null)))
    }

    @Test fun `суть не подменяет заголовок — она живёт подзаголовком объекта`() {
        val understood = entry(
            "invoice.pdf",
            kind = ObjectKind.PDF,
            features = setOf(Feature.IS_PURCHASE),
            metadata = mapOf(META_SEMANTIC_SUMMARY to "Счёт от ООО «Ромашка» на 48 500"),
        )

        assertEquals("Покупка", entryTitle(understood))
    }
}
