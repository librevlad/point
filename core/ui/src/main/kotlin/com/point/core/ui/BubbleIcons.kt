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
import androidx.compose.material.icons.filled.Settings
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
    "reply" -> Icons.Filled.Send
    "link" -> Icons.Filled.Link
    "office" -> Icons.Filled.Article
    "excel" -> Icons.Filled.TableChart
    "renew" -> Icons.Filled.EventRepeat
    "pc" -> Icons.Filled.Computer

    "phone" -> Icons.Filled.Smartphone
    "account" -> Icons.Filled.AccountCircle

    "settings" -> Icons.Filled.Settings
    "scan" -> Icons.Filled.DocumentScanner
    "camera" -> Icons.Filled.PhotoCamera
    "cutout" -> Icons.Filled.ContentCut
    "blur" -> Icons.Filled.BlurOn
    "replace-bg" -> Icons.Filled.Wallpaper
    "ocr" -> Icons.Filled.TextFields

    "text" -> Icons.Filled.TextFields
    "transcribe" -> Icons.Filled.RecordVoiceOver
    "find" -> Icons.Filled.Search
    "ocr-cloud" -> Icons.Filled.Cloud

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

fun bubbleColor(key: String): Color = when (if (key.startsWith("app:")) "open-in" else key) {
    "share" -> Color(0xFFF5610F)
    "save" -> Color(0xFF64748B)
    "save-all" -> Color(0xFF64748B)
    "open" -> Color(0xFF2F80ED)
    "ai" -> Color(0xFF7C4DFF)
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

fun kindIcon(kind: ObjectKind): ImageVector = when (kind) {
    ObjectKind.IMAGE -> Icons.Filled.Image
    ObjectKind.TEXT -> Icons.Filled.Description
    ObjectKind.PDF -> Icons.Filled.PictureAsPdf
    ObjectKind.ZIP -> Icons.Filled.FolderZip
    ObjectKind.OFFICE -> Icons.Filled.Article
    ObjectKind.URL -> Icons.Filled.Link
    ObjectKind.AUDIO -> Icons.Filled.GraphicEq
    ObjectKind.COLLECTION -> Icons.Filled.FolderOpen

    KIND_IDENTIFIER -> Icons.Filled.Tag
    KIND_ADDRESS -> Icons.Filled.Place
    KIND_DATE -> Icons.Filled.Event
    KIND_PHONE -> Icons.Filled.Call
    KIND_EMAIL -> Icons.Filled.Email
    KIND_URL -> Icons.Filled.Link
    KIND_ORGANIZATION -> Icons.Filled.Business
    KIND_PERSON -> Icons.Filled.Person

    else -> Icons.Filled.HelpOutline
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
    else -> "Объект"
}
