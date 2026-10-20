package com.point.desktop.ui

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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.point.core.flow.KIND_ADDRESS
import com.point.core.flow.KIND_AMOUNT
import com.point.core.flow.KIND_DATE
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_IDENTIFIER
import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_PLACE
import com.point.core.flow.KIND_URL
import com.point.core.model.ObjectKind

/*
 * Значки окна — линией, а не заливкой (макет 19.10.2026).
 *
 * Телефонные значки залиты цветом и стоят на цветной плашке; на стекле компьютера макет рисует
 * их тонкой линией светлого цвета на круглой пастельной плашке. Значки самого окна (плюс,
 * стрелки, папка, буфер, экран, ссылка, запись, документ) нарисованы по путям макета; значки
 * действий — те же знаки Material, что на телефоне, только в контурном начертании: одно
 * действие на двух устройствах узнаётся по одному знаку.
 */

/** Линия толщиной 1.5 на сетке 24 — так рисует макет. */
private fun line(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach { d ->
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** Значки самого окна — по путям макета. */
object GlassIcons {
    val Plus = line("plus", "M12 5v14M5 12h14")
    val ChevronDown = line("chevron-down", "M6 10l6 6 6-6")
    val ChevronUp = line("chevron-up", "M6 14l6-6 6 6")
    val ChevronRight = line("chevron-right", "M9 5l7 7-7 7")
    val ChevronLeft = line("chevron-left", "M15 5l-7 7 7 7")
    val Folder = line("folder", "M3 6h6l2 2h10v11H3z")
    val Clipboard = line(
        "clipboard",
        "M10 4h8a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M4 8v12h11",
    )
    val Screen = line(
        "screen",
        "M5 4h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M9 21h6",
    )
    val Link = line(
        "link",
        "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1",
        "M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    )
    val Mic = line("mic", "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M6 11a6 6 0 0 0 12 0M12 17v4")
    val Document = line("document", "M6 3h9l4 4v14H6z", "M9 12h6M9 16h6")
    val Pdf = line("pdf", "M6 3h9l4 4v14H6z", "M8 13h8M8 17h5")
    val Camera = line("camera", "M4 8h3l2-3h6l2 3h3v11H4z", "M12 10a3 3 0 1 0 0 6a3 3 0 1 0 0-6z")
    val Picture = line(
        "picture",
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M3 16l5-5 4 4 3-3 6 6",
        "M16 7.5a1.5 1.5 0 1 0 0 3a1.5 1.5 0 1 0 0-3z",
    )
    val Archive = line("archive", "M3 6h6l2 2h10v11H3z", "M12 11v2M12 15v2")
    val Sparkle = line("sparkle", "M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8z")
    val Check = line("check", "M5 12l4 4 10-10")
    val Phone = line("phone", "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 19V5a2.5 2.5 0 0 1 2.5-2.5z", "M11 18.5h2")
    /**
     * Настройки — шестерёнкой, тем же знаком, что у телефона. Макет рисовал здесь кружок с
     * лучами, и в шапке окна его принимали за лампочку.
     */
    val Gear: ImageVector = Icons.Outlined.Settings
    val Minimize = line("minimize", "M6 12h12")
    val Maximize = line("maximize", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val Restore = line("restore", "M8 8h9a2 2 0 0 1 2 2v9H8z", "M5 16V5h11")
    val Close = line("close", "M6 6l12 12M18 6L6 18")
    val Search = line("search", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4")
    val OpenOut = line("open-out", "M7 17L17 7M9 7h8v8")
    val Send = line("send", "M4 12l16-8-6 16-2-7z")
    val Table = line("table", "M4 5h16v14H4z", "M4 10h16M4 15h16M10 5v14")
    val Copy = Clipboard
}

/** Знак вида объекта — в строке «Недавнего» и в кольце объекта. */
fun kindLine(kind: ObjectKind): ImageVector = when (kind) {
    ObjectKind.IMAGE -> GlassIcons.Picture
    ObjectKind.TEXT -> GlassIcons.Document
    ObjectKind.PDF -> GlassIcons.Pdf
    ObjectKind.OFFICE -> GlassIcons.Document
    ObjectKind.URL -> GlassIcons.Link
    ObjectKind.AUDIO -> GlassIcons.Mic
    ObjectKind.ZIP -> GlassIcons.Archive
    ObjectKind.COLLECTION -> GlassIcons.Folder
    KIND_PHONE -> Icons.Outlined.Call
    KIND_EMAIL -> Icons.Outlined.Email
    KIND_URL -> GlassIcons.Link
    KIND_ADDRESS, KIND_PLACE -> Icons.Outlined.Place
    KIND_DATE -> Icons.Outlined.Event
    KIND_IDENTIFIER -> Icons.Outlined.Tag
    KIND_ORGANIZATION -> Icons.Outlined.Business
    KIND_PERSON -> Icons.Outlined.Person
    KIND_AMOUNT -> Icons.Outlined.Payments
    else -> GlassIcons.Document
}

/**
 * Знак вещи в кольце её экрана — по той же марке, что у телефона (#825, #1233): таблица там
 * таблица, а не «документ». Найденное значение марки не имеет и рисуется знаком своего вида.
 */
fun heroLine(obj: com.point.core.model.PointObject): ImageVector = when (com.point.core.ui.kindMarkOf(obj)) {
    com.point.core.ui.KindMark.SPREADSHEET -> GlassIcons.Table
    com.point.core.ui.KindMark.TEXT, com.point.core.ui.KindMark.DOCUMENT -> GlassIcons.Document
    com.point.core.ui.KindMark.IMAGE -> GlassIcons.Picture
    com.point.core.ui.KindMark.PDF -> GlassIcons.Pdf
    com.point.core.ui.KindMark.ARCHIVE -> GlassIcons.Archive
    com.point.core.ui.KindMark.AUDIO -> GlassIcons.Mic
    com.point.core.ui.KindMark.LINK -> GlassIcons.Link
    com.point.core.ui.KindMark.COLLECTION -> GlassIcons.Folder
    com.point.core.ui.KindMark.UNKNOWN -> kindLine(obj.state.kind)
}

/**
 * Пастельный тон вида: у плашки в списке и у кольца. Тона — палитры макета: запись фиолетовая,
 * ссылка синяя, текст голубой, снимок розовый, документ оранжевый.
 */
fun kindTint(kind: ObjectKind): Color = when (kind) {
    ObjectKind.AUDIO -> PointColors.violet
    ObjectKind.URL, KIND_URL -> PointColors.blue
    ObjectKind.TEXT -> PointColors.cyan
    ObjectKind.IMAGE -> PointColors.pink
    ObjectKind.PDF, ObjectKind.OFFICE -> PointColors.orange
    ObjectKind.ZIP, ObjectKind.COLLECTION -> PointColors.yellow
    KIND_PHONE, KIND_EMAIL, KIND_PERSON, KIND_ORGANIZATION -> PointColors.green
    else -> PointColors.violet
}

/**
 * Знак действия — тот же, что на телефоне (`bubbleIcon`), в контурном начертании.
 *
 * Ключи обязаны совпадать с телефонными: новый ключ, добавленный только там, здесь нарисовался бы
 * запасной молнией. За этим смотрит `GlassIconsTest`.
 */
fun lineIcon(key: String): ImageVector = when (if (key.startsWith("app:")) "open-in" else key) {
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
    "fix" -> Icons.Outlined.AutoFixNormal
    "fix-more" -> Icons.Outlined.AutoFixHigh
    "ask" -> Icons.Outlined.QuestionAnswer
    "cart" -> Icons.Outlined.ShoppingCart
    "reply" -> Icons.Outlined.Send
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

/**
 * Тон плашки действия: телефонный цвет действия, смягчённый к светлому, — на стекле макет держит
 * пастель, а не насыщенную краску.
 */
fun actionTint(color: Color): Color = androidx.compose.ui.graphics.lerp(color, PointColors.text, 0.42f)
