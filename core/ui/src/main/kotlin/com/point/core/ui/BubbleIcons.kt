package com.point.core.ui

import androidx.compose.material.icons.Icons
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.point.core.model.ObjectKind

/** Resolves a [com.point.core.model.Bubble.icon] key to a vector. */
fun bubbleIcon(key: String): ImageVector = when (if (key.startsWith("app:")) "open-in" else key) {
    "share" -> Icons.Filled.Share
    "save" -> Icons.Filled.Save
    "save-all" -> Icons.Filled.SaveAlt
    "open" -> Icons.Filled.OpenInNew
    "pdf" -> Icons.Filled.PictureAsPdf
    "compress" -> Icons.Filled.Compress
    "unzip" -> Icons.Filled.FolderZip
    "pages" -> Icons.Filled.Collections
    "translate" -> Icons.Filled.Translate
    "ai" -> Icons.Filled.AutoAwesome
    "cart" -> Icons.Filled.ShoppingCart
    "basket" -> Icons.Filled.ShoppingBasket
    "reply" -> Icons.Filled.Send
    "link" -> Icons.Filled.Link
    "office" -> Icons.Filled.Article
    "excel" -> Icons.Filled.TableChart
    "renew" -> Icons.Filled.EventRepeat
    "pc" -> Icons.Filled.Computer
    // Круг устройств (#472): телефон рядом с компьютером, и аккаунт, которым они связаны.
    "phone" -> Icons.Filled.Smartphone
    "account" -> Icons.Filled.AccountCircle
    "scan" -> Icons.Filled.DocumentScanner
    "camera" -> Icons.Filled.PhotoCamera
    "cutout" -> Icons.Filled.ContentCut
    "blur" -> Icons.Filled.BlurOn
    "replace-bg" -> Icons.Filled.Wallpaper
    "ocr" -> Icons.Filled.TextFields
    // #491: выход разговора — «Забрать ответ». Знак тот же, что у всякого текста в Point, а
    // ключ отдельный: забирают не распознавание, а сказанное моделью.
    "text" -> Icons.Filled.TextFields
    "transcribe" -> Icons.Filled.RecordVoiceOver
    "find" -> Icons.Filled.Search
    "ocr-cloud" -> Icons.Filled.Cloud
    // Просто «облако» — знак того, что объект уходит с устройства. Им подписан экран согласия:
    // отдельный ключ, а не заимствованное «распознать в облаке», потому что спрашивают не про
    // распознавание, а про саму отправку.
    "cloud" -> Icons.Filled.Cloud
    "qr" -> Icons.Filled.QrCode2
    "qr-scan" -> Icons.Filled.QrCodeScanner
    "call" -> Icons.Filled.Call
    "copy" -> Icons.Filled.ContentCopy
    "list" -> Icons.Filled.FormatListBulleted
    "message" -> Icons.Filled.Sms
    "email" -> Icons.Filled.Email
    "map" -> Icons.Filled.Place
    "event" -> Icons.Filled.Event
    "contact" -> Icons.Filled.PersonAdd
    "open-in" -> Icons.Filled.Apps
    else -> Icons.Filled.Bolt
}

/**
 * The vivid circle colour for a bubble, from the Point.dc.html palette. The
 * universal trio (share/save/ai) gets distinct anchors so it never collides with
 * the type-specific actions it always sits beside.
 */
fun bubbleColor(key: String): Color = when (if (key.startsWith("app:")) "open-in" else key) {
    "share" -> Color(0xFFF5610F)    // orange — brand "send"
    "save" -> Color(0xFF64748B)     // slate — neutral universal
    "save-all" -> Color(0xFF64748B) // slate — neutral universal (collection)
    "open" -> Color(0xFF2F80ED)     // blue — open externally
    "ai" -> Color(0xFF7C4DFF)       // purple — intelligence
    "cart" -> Color(0xFF16A34A)     // green — groceries
    "basket" -> Color(0xFFF2994A)   // amber — the growing pile
    "reply" -> Color(0xFF7C4DFF)    // purple — AI-composed reply
    "pdf" -> Color(0xFF2F80ED)      // blue — documents
    "link" -> Color(0xFF2F80ED)     // blue — open link
    "compress" -> Color(0xFF0EA5A5) // teal — image
    "pc" -> Color(0xFF0EA5E9)       // sky — continue on PC
    "phone" -> Color(0xFF0EA5E9)    // sky — тот же цвет семьи устройств: телефон и ПК — одно и то же сословие
    "account" -> Color(0xFF7B5CFF) // АКЦЕНТ1 — аккаунт и есть главное действие своего экрана
    "scan" -> Color(0xFF6366F1)     // indigo — scan
    "camera" -> Color(0xFF6366F1)   // indigo — снять кадр: та же семья, что скан
    "cutout" -> Color(0xFF0EA5A5)   // teal — remove background
    "blur" -> Color(0xFF6366F1)     // indigo — blur background
    "replace-bg" -> Color(0xFF7C4DFF) // purple — replace background
    "ocr" -> Color(0xFF16A34A)      // green — recognise text
    "text" -> Color(0xFF16A34A)     // green — текст, откуда бы он ни пришёл
    "transcribe" -> Color(0xFF16A34A) // green — тоже «прочитать сказанное», только ушами
    "find" -> Color(0xFF16A34A)     // green — читать прочитанное: поиск живёт рядом с распознаванием
    "ocr-cloud" -> Color(0xFF2F80ED) // blue — recognise in the cloud
    "cloud" -> Color(0xFF00E0FF)    // АКЦЕНТ2 — то, что уходит с устройства, светится циан
    "qr" -> Color(0xFF0EA5A5)       // teal — produces an image
    "qr-scan" -> Color(0xFF2F80ED)  // blue — read a QR
    "office" -> Color(0xFF16A34A)   // green — documents/data
    "excel" -> Color(0xFF15803D)    // deeper green — spreadsheet
    "renew" -> Color(0xFF0F766E)    // deep teal — тот же документ на новый период
    "unzip" -> Color(0xFFF2994A)    // amber — archive
    "pages" -> Color(0xFF2F80ED)    // blue — PDF pages
    "translate" -> Color(0xFFEC4899) // pink — translate
    "call" -> Color(0xFF16A34A)      // green — call
    "copy" -> Color(0xFF64748B)      // slate — copy to clipboard
    "list" -> Color(0xFF0EA5A5)      // teal — collect entities into a list
    "message" -> Color(0xFF2F80ED)   // blue — message
    "email" -> Color(0xFF2F80ED)     // blue — email
    "map" -> Color(0xFFEA4335)       // red — map pin
    "event" -> Color(0xFF6366F1)     // indigo — calendar event
    "contact" -> Color(0xFF16A34A)   // green — add contact
    "open-in" -> Color(0xFF2F80ED)   // blue — open externally
    else -> Color(0xFF9AA0A6)        // grey — everything else
}

/** Icon representing the current object's kind, shown in the screen header.
 *  Один тип уже перерос иконку: у таблицы есть собственный рисованный знак — `objectMark` /
 *  `SpreadsheetMark` (#295), и герой экрана спрашивает сначала его. */
fun kindIcon(kind: ObjectKind): ImageVector = when (kind) {
    ObjectKind.IMAGE -> Icons.Filled.Image
    ObjectKind.TEXT -> Icons.Filled.Description
    ObjectKind.PDF -> Icons.Filled.PictureAsPdf
    ObjectKind.ZIP -> Icons.Filled.FolderZip
    ObjectKind.OFFICE -> Icons.Filled.Article
    ObjectKind.URL -> Icons.Filled.Link
    ObjectKind.AUDIO -> Icons.Filled.GraphicEq
    ObjectKind.COLLECTION -> Icons.Filled.FolderOpen
    // Things extraction finds in the world (#222) — each looks like what it is.
    KIND_IDENTIFIER -> Icons.Filled.Tag
    KIND_ADDRESS -> Icons.Filled.Place
    KIND_DATE -> Icons.Filled.Event
    KIND_PHONE -> Icons.Filled.Call
    KIND_EMAIL -> Icons.Filled.Email
    KIND_URL -> Icons.Filled.Link
    KIND_ORGANIZATION -> Icons.Filled.Business
    KIND_PERSON -> Icons.Filled.Person
    // Kinds are open — an extraction kind with no icon of its own falls back here.
    else -> Icons.Filled.HelpOutline
}

/** Friendly Russian title for an object kind — used when the object has no name, instead of the
 *  raw MIME (an AI/text result would otherwise read "text/markdown", #77). */
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
    else -> "Объект"
}
