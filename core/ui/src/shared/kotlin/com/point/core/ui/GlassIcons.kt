package com.point.core.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import com.point.core.flow.KIND_EMAIL
import com.point.core.flow.KIND_ORGANIZATION
import com.point.core.flow.KIND_PERSON
import com.point.core.flow.KIND_PHONE
import com.point.core.flow.KIND_URL
import com.point.core.model.ObjectKind
import com.point.core.model.PointObject

/*
 * Значки стекла — линией, а не заливкой (макет 19.10.2026; телефон — с 20.10.2026).
 *
 * Прежде телефон заливал знак цветом действия на цветной плашке, а компьютер рисовал тот же
 * знак контуром и держал свою копию таблицы. Макет рисует оба устройства одинаково: тонкая
 * светлая линия на круглой пастельной плашке. Знаки самого Point (плюс, стрелки, папка, буфер,
 * экран, ссылка, запись, документ) нарисованы по путям макета; знаки действий — знаки Material
 * в контурном начертании (`bubbleIcon`). Таблица одна, и разъехаться ей не с чем.
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

/** Значки самого Point — по путям макета. */
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

/**
 * Знак вещи в кольце её экрана — по той же марке, что выбирает имя вида (#825, #1233): таблица
 * там таблица, а не «документ». Найденное значение марки не имеет и рисуется знаком своего вида.
 */
fun heroLine(obj: PointObject): ImageVector = when (kindMarkOf(obj)) {
    KindMark.SPREADSHEET -> GlassIcons.Table
    KindMark.TEXT, KindMark.DOCUMENT -> GlassIcons.Document
    KindMark.IMAGE -> GlassIcons.Picture
    KindMark.PDF -> GlassIcons.Pdf
    KindMark.ARCHIVE -> GlassIcons.Archive
    KindMark.AUDIO -> GlassIcons.Mic
    KindMark.LINK -> GlassIcons.Link
    KindMark.COLLECTION -> GlassIcons.Folder
    KindMark.UNKNOWN -> kindIcon(obj.state.kind)
}

/**
 * Пастельный тон вида: у плашки в списке и у кольца. Тона — палитры макета: запись фиолетовая,
 * ссылка синяя, текст голубой, снимок розовый, документ оранжевый.
 */
fun kindTint(kind: ObjectKind): Color = when (kind) {
    ObjectKind.AUDIO -> PointPalette.violet
    ObjectKind.URL, KIND_URL -> PointPalette.blue
    ObjectKind.TEXT -> PointPalette.cyan
    ObjectKind.IMAGE -> PointPalette.pink
    ObjectKind.PDF, ObjectKind.OFFICE -> PointPalette.orange
    ObjectKind.ZIP, ObjectKind.COLLECTION -> PointPalette.yellow
    KIND_PHONE, KIND_EMAIL, KIND_PERSON, KIND_ORGANIZATION -> PointPalette.green
    else -> PointPalette.violet
}

/**
 * Тон плашки действия: цвет действия (`bubbleColor`), смягчённый к светлому, — на стекле макет
 * держит пастель, а не насыщенную краску.
 */
fun actionTint(color: Color): Color = androidx.compose.ui.graphics.lerp(color, PointPalette.text, 0.42f)
