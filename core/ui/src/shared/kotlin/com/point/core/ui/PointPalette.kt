package com.point.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Тёмная палитра Point — одна на телефон и ПК (#851).
 *
 * До этого те же десять цветов были набраны литералами дважды: в `theme/PointTheme.kt`
 * внутри `darkColorScheme(...)` и в `desktop/ui/PointDesktopTheme.kt` в `object PointColors`.
 * В шапке десктопного файла было написано, откуда они взяты, — но приписка не мешает
 * поменять оттенок поверхности на телефоне и забыть про ПК, и ни один тест не покраснеет.
 * Ровно та же дыра, что была у значков до #849.
 *
 * Каталог `src/shared/kotlin` компилируют обе стороны, каждая своим Compose, поэтому здесь
 * можно держать не описание палитры, а саму палитру.
 */
object PointPalette {

    /** Холст под всем: окно ПК и фон экрана телефона. */
    val canvas = Color(0xFF0B0D10)

    /** Карточка, плашка, лист — то, что лежит на холсте. */
    val surface = Color(0xFF14161C)

    /** Углубление внутри поверхности: поле ввода, вложенный блок. */
    val surfaceDeep = Color(0xFF1B1E27)

    /** Кромка между поверхностями. */
    val border = Color(0xFF242833)

    val text = Color(0xFFFFFFFF)

    /** Второй по важности текст: подпись, обещание действия, счётчик. */
    val muted = Color(0xFFA1A6B3)

    /** Цвет самого Point: главное действие, портал, знак приложения. */
    val violet = Color(0xFF7B5CFF)

    /** Дальний конец градиента главного действия. */
    val blue = Color(0xFF4E7BFF)

    /** Второй акцент: облако, связь между устройствами. */
    val cyan = Color(0xFF00E0FF)
}

/**
 * Тона строки действия: карточка светлее сверху и темнее снизу, будто на неё падает свет.
 *
 * Лежали копией в обоих `PortalSurfaces.kt` с той же припиской «источник правды —
 * телефонный файл».
 */
object PortalTones {

    val rowTop = Color(0xFF1A1D25)

    val rowBottom = Color(0xFF121419)

    /** Основа плашки-иконки, поверх которой светится цвет действия. */
    val plateBase = Color(0xFF1F222B)

    /** Светлая кромка сверху — то, из-за чего карточка выглядит выпуклой, а не нарисованной. */
    val topHighlight = Color(0x12FFFFFF)
}

val PortalCardShape = RoundedCornerShape(18.dp)

val PortalPlateShape = RoundedCornerShape(14.dp)
