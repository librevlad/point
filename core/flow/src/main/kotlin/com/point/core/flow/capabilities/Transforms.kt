package com.point.core.flow.capabilities

import com.point.core.flow.Capability
import com.point.core.flow.CapabilityMeta
import com.point.core.flow.Cost
import com.point.core.flow.Latency
import com.point.core.model.ActionYield
import com.point.core.model.CapabilityId
import com.point.core.model.Feature
import com.point.core.model.Intent
import com.point.core.model.ObjectKind
import com.point.core.model.ObjectState
import com.point.core.model.isFileBacked

/**
 * Чем на самом деле оказывается офисный документ, ставший PDF (#558).
 *
 * Одна строка на два места: ею способность **обещает** до тапа, ею же реализация **помечает**
 * результат, и сторож сверяет одно с другим. Переехала сюда вместе со способностью — обещание и
 * его проверка не должны жить в разных модулях.
 */
const val OFFICE_PDF_SUBSTANCE = "PDF с текстом документа"

/*
 * Преобразования общего словаря — намерения, у которых важен РЕЗУЛЬТАТ, а не место (И1).
 *
 * Сюда не едут доставки и эффекты: «Скопировать» значит «в МОЙ буфер», «Сохранить» — «на МОЙ
 * диск», «Открыть» — «ЗДЕСЬ». У них место назначения и есть часть намерения, и слить их значило бы
 * разрешить `Resolver` молча выполнить не то, что человек назвал. Правило и таблица —
 * `docs/DESKTOP-CONTRACT.md`.
 *
 * Реализации остаются у каждого устройства свои: телефон распаковывает архив сам, компьютер —
 * своим способом; выбирает `Resolver`.
 */

/**
 * text / url → a QR-code image (#85 "превратить в…"). A true type transform: the result is an
 * IMAGE, so the whole image action set (save / share / open) opens on it. On-device, no network.
 */
class QrCapability  : Capability {
    override val id = ID
    override val icon = "qr"
    override val meta = CapabilityMeta(priority = 45)
    override fun label(state: ObjectState) = "QR-код"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.TEXT || state.kind == ObjectKind.URL
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)
    override fun intents(state: ObjectState) = setOf(Intent.PREPARE)

    companion object { val ID = CapabilityId("qr") }
}

/** Archive (zip/tar/gz/bz2/xz/7z/rar) -> a COLLECTION of the unpacked files. */
class ArchiveCapability  : Capability {
    override val id = ID
    override val icon = "unzip"

    /** Не [Latency.INSTANT] (#288): большой архив распаковывается секунды и десятки секунд — та же
     *  правка и по той же причине, что у «Страницы». Работа растёт с содержимым архива, поэтому
     *  и не [Latency.SLOW]: она рассказывает о себе на объекте, а не забирает экран. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Распаковать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.ZIP
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.COLLECTION)

    companion object { val ID = CapabilityId("archive") }
}

/** Office document (docx/xlsx/pptx) -> extracted plain text. */
class OfficeCapability  : Capability {
    override val id = ID
    override val icon = "office"

    /** Не [Latency.INSTANT] (#288): разбор docx/xlsx/pptx идёт секунды на большом файле — ровно
     *  та же работа и те же слова, что у «В PDF» над документом, а тот объявлен [Latency.FAST]. */
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) = "Извлечь текст"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.OFFICE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.TEXT)

    companion object { val ID = CapabilityId("office") }
}

/** image -> JPEG-compressed image. */
class ImageCapability  : Capability {
    override val id = ID
    override val icon = "compress"

    /**
     * Не [Latency.INSTANT] (#288) — та же правка и по той же причине, что у «Скана».
     *
     * «Сжать» декодирует снимок целиком в память и кодирует его обратно: на кадре с камеры это
     * секунды, а не мгновение. Мгновенным объявлено оно было молча, по умолчанию — и вместе с
     * объявлением получало мгновенный тир пузырька, то есть обещало человеку то, чего не делает.
     */
    override val meta = CapabilityMeta(latency = Latency.FAST)

    override fun label(state: ObjectState) = "Сжать"
    override fun accepts(state: ObjectState) = state.kind == ObjectKind.IMAGE
    override fun produces(state: ObjectState) = ObjectState(ObjectKind.IMAGE)

    companion object { val ID = CapabilityId("image") }
}

/**
 * «Дать ссылку» (#388) — отдать файл человеку, у которого Point не стоит.
 *
 * Самая маленькая форма Drop: ни аккаунтов, ни страниц, ни отдельного сервера. Получатель
 * открывает ссылку браузером и получает файл; ссылка живёт сутки и умирает сама.
 *
 * Действие сетевое и **не бесплатное по приватности**: в отличие от всего остального, что возит
 * релей, файл по ссылке лежит на сервере открытым — ключа у чужого человека нет. Поэтому оно
 * стоит за явным тапом и названо прямо.
 */
class DropLinkCapability  : Capability {
    override val id = ID
    override val icon = "link"
    override val meta = CapabilityMeta(
        priority = 35,
        cost = Cost.FREE,
        latency = Latency.SLOW,
        network = true,
    )

    override fun label(state: ObjectState) = "Дать ссылку"

    /**
     * Ссылке ссылку не дают (#457).
     *
     * Объект-URL — это сорок байт текста со ссылкой внутри, и «Дать ссылку» загрузило бы на
     * сервер **их**: человек получил бы ссылку на ссылку, а тот, кому он её отправит, — текстовый
     * файлик вместо страницы. Это единственное действие, чей собственный результат (`produces`
     * = URL) снова попадал в его же `accepts`: петля, у которой второй виток бессмыслен.
     *
     * Исключение то же самое, что уже стоит у «Открыть» и «Открыть в…»: у ссылки свои действия
     * («Открыть ссылку», «Скопировать», «Код»), и подменять их загрузкой на сервер незачем.
     */
    override fun accepts(state: ObjectState) =
        state.kind.isFileBacked && state.kind != ObjectKind.URL

    override fun produces(state: ObjectState) = ObjectState(ObjectKind.URL)

    companion object { val ID = CapabilityId("drop-link") }
}

/** image/text/office -> PDF, and PDF -> extracted text. */
class PdfCapability : Capability {
    override val id = ID
    override val icon = "pdf"
    // Real rendering/extraction work — honest latency keeps it out of the «Мгновенные» tier (#114).
    override val meta = CapabilityMeta(latency = Latency.FAST)
    override fun label(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) "Извлечь текст" else "В PDF"
    override fun accepts(state: ObjectState) =
        state.kind in setOf(ObjectKind.IMAGE, ObjectKind.TEXT, ObjectKind.OFFICE) ||
            // A scan (image-only PDF) has no text layer — "Извлечь текст" would only dead-end.
            (state.kind == ObjectKind.PDF && !state.has(Feature.IS_IMAGE_PDF))
    override fun produces(state: ObjectState) =
        if (state.kind == ObjectKind.PDF) ObjectState(ObjectKind.TEXT) else ObjectState(ObjectKind.PDF)

    /**
     * Подпись говорит о результате **по существу**, а не по расширению файла (#558).
     *
     * Жалоба владельца дословно: «Word в PDF молча дал не то, что человек хотел». Разбор: офисный
     * файл на телефоне превращается в PDF **пересказом** — [PdfRealizer.officeToPdf] вынимает из
     * документа текст и печатает его заново. На выходе настоящий PDF, поэтому вид совпадал с
     * обещанным и сторож [com.point.core.flow.yieldSurprise] молчал; что внутри пересказ, а не
     * документ, человек выяснял, открыв файл.
     *
     * Пока конвертация такая (её чинит #403), обещать голое «вернёт PDF» — неправда умолчанием.
     * Здесь чинится подпись, а не поведение: тот же файл, но сказано, что в нём будет.
     */
    override fun yields(state: ObjectState) = when (state.kind) {
        ObjectKind.PDF -> ActionYield.New(ObjectKind.TEXT)
        ObjectKind.OFFICE -> ActionYield.New(ObjectKind.PDF, "$OFFICE_PDF_SUBSTANCE · без оформления")
        else -> ActionYield.New(ObjectKind.PDF)
    }

    companion object { val ID = CapabilityId("pdf") }
}
