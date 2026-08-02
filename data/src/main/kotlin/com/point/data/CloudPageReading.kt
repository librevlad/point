package com.point.data

import com.point.core.flow.AtomCodec
import com.point.core.flow.META_CLOUD_ATOMS_REF
import com.point.core.flow.ObjectStore
import com.point.core.model.PointObject
import java.io.File
import javax.inject.Inject

/**
 * Второе чтение страницы кладётся **рядом** с первым (#280).
 *
 * Слой облака персистится в scratch и адресуется своим ключом [META_CLOUD_ATOMS_REF]; ключ
 * офлайнового чтения (`ocr.atoms.ref`) не трогается вовсе. Это и есть весь смысл второго
 * читателя: на эталонной ведомости телефонный движок выдаёт кашу, облако читает таблицу — и
 * ценность появляется не от того, что одно вытеснило другое, а от того, что их **два** и они
 * расходятся там, где надо перечитывать.
 *
 * Сети здесь нет по умолчанию нигде: `atomRecognizer` в DI по-прежнему офлайновый, фоновое
 * обогащение в облако не ходит. Вызывать это можно только из действия с
 * `CapabilityMeta(network = true)` — то есть после явного тапа и через ту же дверь согласия,
 * что и все остальные сетевые действия.
 *
 * Отказ не маскируется: если ни один бесплатный слой не прочитал, исключение из
 * [FallbackAtomRecognizer] летит наружу. Вернуть «прочитали, ничего нет» было бы красивой
 * видимостью вместо статуса.
 */
class CloudPageReading @Inject constructor(
    private val store: ObjectStore,
    private val readers: FallbackAtomRecognizer,
) {

    /** Есть ли в этой сборке хоть один настроенный бесплатный слой. */
    val available: Boolean get() = readers.available

    /**
     * Прочитать страницу облаком и сохранить слой.
     *
     * @return метаданные, которые вызывающий **добавляет** к метаданным объекта. Здесь ровно один
     *   ключ, и он новый — слияние никогда не может затереть офлайновый слой.
     */
    suspend fun read(obj: PointObject): Map<String, String> {
        val layer = readers.read(obj)
        val ref = store.newScratchFile("cloud-atoms.tsv")
        File(ref.value).writeText(AtomCodec.encode(layer))
        return mapOf(META_CLOUD_ATOMS_REF to ref.value)
    }
}
