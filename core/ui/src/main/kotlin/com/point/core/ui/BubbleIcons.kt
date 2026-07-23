package com.point.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.point.core.model.Intent
import com.point.core.model.ObjectKind

/** Resolves a [com.point.core.model.Bubble.icon] key to a vector. */
fun bubbleIcon(key: String): ImageVector = when (key) {
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
    "link" -> Icons.Filled.Link
    "office" -> Icons.Filled.Article
    "excel" -> Icons.Filled.TableChart
    "scan" -> Icons.Filled.DocumentScanner
    "ocr" -> Icons.Filled.TextFields
    "ocr-cloud" -> Icons.Filled.Cloud
    "qr" -> Icons.Filled.QrCode2
    "call" -> Icons.Filled.Call
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
fun bubbleColor(key: String): Color = when (key) {
    "share" -> Color(0xFFF5610F)    // orange — brand "send"
    "save" -> Color(0xFF64748B)     // slate — neutral universal
    "save-all" -> Color(0xFF64748B) // slate — neutral universal (collection)
    "open" -> Color(0xFF2F80ED)     // blue — open externally
    "ai" -> Color(0xFF7C4DFF)       // purple — intelligence
    "pdf" -> Color(0xFF2F80ED)      // blue — documents
    "link" -> Color(0xFF2F80ED)     // blue — open link
    "compress" -> Color(0xFF0EA5A5) // teal — image
    "scan" -> Color(0xFF6366F1)     // indigo — scan
    "ocr" -> Color(0xFF16A34A)      // green — recognise text
    "ocr-cloud" -> Color(0xFF2F80ED) // blue — recognise in the cloud
    "qr" -> Color(0xFF0EA5A5)       // teal — produces an image
    "office" -> Color(0xFF16A34A)   // green — documents/data
    "excel" -> Color(0xFF15803D)    // deeper green — spreadsheet
    "unzip" -> Color(0xFFF2994A)    // amber — archive
    "pages" -> Color(0xFF2F80ED)    // blue — PDF pages
    "translate" -> Color(0xFFEC4899) // pink — translate
    "call" -> Color(0xFF16A34A)      // green — call
    "message" -> Color(0xFF2F80ED)   // blue — message
    "email" -> Color(0xFF2F80ED)     // blue — email
    "map" -> Color(0xFFEA4335)       // red — map pin
    "event" -> Color(0xFF6366F1)     // indigo — calendar event
    "contact" -> Color(0xFF16A34A)   // green — add contact
    "open-in" -> Color(0xFF2F80ED)   // blue — open externally
    else -> Color(0xFF9AA0A6)        // grey — everything else
}

/** Icon for a user [Intent] — the intent-first surface (Понять / Подготовить / Отправить). */
fun intentIcon(intent: Intent): ImageVector = when (intent) {
    Intent.UNDERSTAND -> Icons.Filled.Lightbulb
    Intent.PREPARE -> Icons.Filled.AutoFixHigh
    Intent.OPEN -> Icons.Filled.OpenInNew
    Intent.SEND -> Icons.Filled.Send
}

/** Russian label for a user [Intent]. */
fun intentTitle(intent: Intent): String = when (intent) {
    Intent.UNDERSTAND -> "Понять"
    Intent.PREPARE -> "Подготовить"
    Intent.OPEN -> "Открыть"
    Intent.SEND -> "Отправить"
}

/** Circle colour for a user [Intent], from the Point palette. */
fun intentColor(intent: Intent): Color = when (intent) {
    Intent.UNDERSTAND -> Color(0xFF7C4DFF) // purple — understanding
    Intent.PREPARE -> Color(0xFF0EA5A5)    // teal — prepare / transform
    Intent.OPEN -> Color(0xFF2F80ED)       // blue — open externally
    Intent.SEND -> Color(0xFFF5610F)       // orange — brand "send"
}

/** Icon representing the current object's kind, shown in the screen header. */
fun kindIcon(kind: ObjectKind): ImageVector = when (kind) {
    ObjectKind.IMAGE -> Icons.Filled.Image
    ObjectKind.TEXT -> Icons.Filled.Description
    ObjectKind.PDF -> Icons.Filled.PictureAsPdf
    ObjectKind.ZIP -> Icons.Filled.FolderZip
    ObjectKind.OFFICE -> Icons.Filled.Article
    ObjectKind.URL -> Icons.Filled.Link
    ObjectKind.COLLECTION -> Icons.Filled.FolderOpen
    ObjectKind.UNKNOWN -> Icons.Filled.HelpOutline
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
    ObjectKind.COLLECTION -> "Коллекция"
    ObjectKind.UNKNOWN -> "Объект"
}
