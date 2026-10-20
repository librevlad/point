package com.point.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_AMOUNT
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.KIND_PLACE
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.model.ObjectKind

/**
 * Знак действия — одна таблица на телефон и ПК (#849), контуром (макет 19.10.2026).
 *
 * Компьютер рисовал знаки контуром и держал свою копию этой таблицы (`lineIcon`), телефон —
 * заливкой отсюда. С переходом телефона на стекло (решение владельца 20.10.2026) знак у
 * действия один и в одном начертании: та же фигура Material, что была залита, теперь линией.
 */
fun bubbleIcon(key: String): ImageVector = when (if (key.startsWith("app:")) "open-in" else key) {
    "share" -> Icons.Outlined.Share
    "save" -> Icons.Outlined.Save
    "save-all" -> Icons.Outlined.SaveAlt
    "open" -> Icons.Outlined.OpenInNew
    "pdf" -> Icons.Outlined.PictureAsPdf
    "compress" -> Icons.Outlined.Compress
    "unzip" -> Icons.Outlined.FolderZip
    "folder" -> Icons.Outlined.FolderOpen
    "print" -> Icons.Outlined.Print
    "pages" -> Icons.Outlined.Collections
    "translate" -> Icons.Outlined.Translate
    "ai" -> Icons.Outlined.AutoAwesome
    // Четыре соседа носили один знак и читались как дубли одного действия (#1384):
    // «Понять», «Исправить ошибки», «Исправить сильнее», «AI». Тон у них общий — они и
    // правда одна семья, — а знак у каждого свой, иначе список врёт про их число.
    "fix" -> Icons.Outlined.AutoFixNormal
    "fix-more" -> Icons.Outlined.AutoFixHigh
    "ask" -> Icons.Outlined.QuestionAnswer
    "cart" -> Icons.Outlined.ShoppingCart
    "reply" -> Icons.Outlined.Send

    // Ссылка нарисована по пути макета: знак Material у неё в контуре почти не читается.
    "link" -> GlassIcons.Link
    "office" -> Icons.Outlined.Article
    "excel" -> Icons.Outlined.TableChart
    "renew" -> Icons.Outlined.EventRepeat
    "pc" -> Icons.Outlined.Computer

    "phone" -> Icons.Outlined.Smartphone
    "account" -> Icons.Outlined.AccountCircle

    "settings" -> Icons.Outlined.Settings
    "scan" -> Icons.Outlined.DocumentScanner
    "camera" -> Icons.Outlined.PhotoCamera
    "cutout" -> Icons.Outlined.ContentCut
    // «Взять фрагмент» — та же группа преобразований, что вырезание (#1263).
    "crop" -> Icons.Outlined.Crop
    "blur" -> Icons.Outlined.BlurOn
    "replace-bg" -> Icons.Outlined.Wallpaper
    "ocr" -> Icons.Outlined.TextFields

    "text" -> Icons.Outlined.TextFields
    "transcribe" -> Icons.Outlined.RecordVoiceOver
    "find" -> Icons.Outlined.Search
    "ocr-cloud" -> Icons.Outlined.Cloud

    "cloud" -> Icons.Outlined.Cloud
    "qr" -> Icons.Outlined.QrCode2
    "qr-scan" -> Icons.Outlined.QrCodeScanner
    "call" -> Icons.Outlined.Call
    "copy" -> Icons.Outlined.ContentCopy
    "list" -> Icons.Outlined.FormatListBulleted
    "message" -> Icons.Outlined.Sms
    "email" -> Icons.Outlined.Email
    "map" -> Icons.Outlined.Place
    "event" -> Icons.Outlined.Event
    "contact" -> Icons.Outlined.PersonAdd
    "open-in" -> Icons.Outlined.Apps
    else -> Icons.Outlined.Bolt
}

fun bubbleColor(key: String): Color = when (if (key.startsWith("app:")) "open-in" else key) {
    "share" -> Color(0xFFF5610F)
    "save" -> Color(0xFF64748B)
    "save-all" -> Color(0xFF64748B)
    "open" -> Color(0xFF2F80ED)
    "ai" -> Color(0xFF7C4DFF)
    // Одна семья — один тон: врозь их разводит знак, а не цвет (#1384).
    "fix" -> Color(0xFF7C4DFF)
    "fix-more" -> Color(0xFF7C4DFF)
    "ask" -> Color(0xFF7C4DFF)
    "cart" -> Color(0xFF16A34A)
    "reply" -> Color(0xFF7C4DFF)
    "pdf" -> Color(0xFF2F80ED)
    "link" -> Color(0xFF2F80ED)
    "compress" -> Color(0xFF0EA5A5)
    "pc" -> Color(0xFF0EA5E9)
    "phone" -> Color(0xFF0EA5E9)
    "account" -> Color(0xFF7B5CFF)

    "settings" -> Color(0xFF64748B)
    "scan" -> Color(0xFF6366F1)
    "camera" -> Color(0xFF6366F1)
    "cutout" -> Color(0xFF0EA5A5)
    "crop" -> Color(0xFF0EA5A5)
    "blur" -> Color(0xFF6366F1)
    "replace-bg" -> Color(0xFF7C4DFF)
    "ocr" -> Color(0xFF16A34A)
    "text" -> Color(0xFF16A34A)
    "transcribe" -> Color(0xFF16A34A)
    "find" -> Color(0xFF16A34A)
    "ocr-cloud" -> Color(0xFF2F80ED)
    "cloud" -> Color(0xFF00E0FF)
    "qr" -> Color(0xFF0EA5A5)
    "qr-scan" -> Color(0xFF2F80ED)
    "office" -> Color(0xFF16A34A)
    "excel" -> Color(0xFF15803D)
    "renew" -> Color(0xFF0F766E)
    "unzip" -> Color(0xFFF2994A)
    "folder" -> Color(0xFF8B93A7)
    "print" -> Color(0xFF5B8DEF)
    "pages" -> Color(0xFF2F80ED)
    "translate" -> Color(0xFFEC4899)
    "call" -> Color(0xFF16A34A)
    "copy" -> Color(0xFF64748B)
    "list" -> Color(0xFF0EA5A5)
    "message" -> Color(0xFF2F80ED)
    "email" -> Color(0xFF2F80ED)
    "map" -> Color(0xFFEA4335)
    "event" -> Color(0xFF6366F1)
    "contact" -> Color(0xFF16A34A)
    "open-in" -> Color(0xFF2F80ED)
    else -> Color(0xFF9AA0A6)
}

/**
 * Знак вида объекта — в строке «Недавнего», у найденного, в пути и в кольце вещи без марки.
 *
 * Знаки Point (снимок, документ, ссылка, запись, папка) — по путям макета; найденные значения —
 * контуры Material. Неизвестное рисуется документом: знак вопроса читался «Point не понял»,
 * хотя у вещи просто нет своего знака.
 */
fun kindIcon(kind: ObjectKind): ImageVector = when (kind) {
    ObjectKind.IMAGE -> GlassIcons.Picture
    ObjectKind.TEXT -> GlassIcons.Document
    ObjectKind.PDF -> GlassIcons.Pdf
    ObjectKind.ZIP -> GlassIcons.Archive
    ObjectKind.OFFICE -> GlassIcons.Document
    ObjectKind.URL -> GlassIcons.Link
    ObjectKind.AUDIO -> GlassIcons.Mic
    ObjectKind.COLLECTION -> GlassIcons.Folder

    KIND_IDENTIFIER -> Icons.Outlined.Tag
    KIND_ADDRESS, KIND_PLACE -> Icons.Outlined.Place
    KIND_DATE -> Icons.Outlined.Event
    KIND_PHONE -> Icons.Outlined.Call
    KIND_EMAIL -> Icons.Outlined.Email
    KIND_URL -> GlassIcons.Link
    KIND_ORGANIZATION -> Icons.Outlined.Business
    KIND_PERSON -> Icons.Outlined.Person
    KIND_AMOUNT -> Icons.Outlined.Payments

    else -> GlassIcons.Document
}

fun kindLabel(kind: ObjectKind): String = when (kind) {
    ObjectKind.IMAGE -> "Изображение"
    ObjectKind.TEXT -> "Текст"
    ObjectKind.PDF -> "PDF"
    ObjectKind.ZIP -> "Архив"
    ObjectKind.OFFICE -> "Документ"
    ObjectKind.URL -> "Ссылка"
    ObjectKind.AUDIO -> "Запись"
    ObjectKind.COLLECTION -> "Коллекция"
    KIND_IDENTIFIER -> "Номер"
    KIND_ADDRESS -> "Адрес"
    KIND_DATE -> "Дата"
    KIND_PHONE -> "Телефон"
    KIND_EMAIL -> "Почта"
    KIND_URL -> "Ссылка"
    KIND_ORGANIZATION -> "Организация"
    KIND_PERSON -> "Человек"

    // Найденное значение носит имя своего вида (#1121): «140 · Объект» не говорил
    // человеку ничего, тогда как соседние находки назывались «Адрес» и «Дата».
    KIND_AMOUNT -> "Сумма"
    KIND_PLACE -> "Место"
    else -> "Объект"
}
