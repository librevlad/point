package com.point.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.point.core.ui.PointPalette
import com.point.core.ui.R

/*
 * Шрифт телефона — Ubuntu, моно-строки — Ubuntu Mono (макет владельца 19.10.2026, телефон —
 * решение 20.10.2026). Тот же файл пишет окно компьютера: он лежит здесь, в `res/font`, а ПК
 * подключает этот каталог ресурсами.
 *
 * Каждый вес — свой файл, поэтому вес текста задаёт сам файл, а не ось переменного шрифта:
 * беды #626 (весь текст ExtraLight по умолчанию оси) здесь не бывает по устройству. Полужирного
 * файла у Ubuntu нет — `SemiBold` берёт ближайший больший, жирный.
 */
private val Ubuntu = FontFamily(
    Font(R.font.ubuntu_regular, FontWeight.Normal),
    Font(R.font.ubuntu_medium, FontWeight.Medium),
    Font(R.font.ubuntu_bold, FontWeight.Bold),
)

private val UbuntuMono = FontFamily(Font(R.font.ubuntu_mono_regular, FontWeight.Normal))

private fun ubuntu(
    size: Float,
    weight: FontWeight = FontWeight.Normal,
    line: Float = size * 1.3f,
    spacing: TextUnit = 0.sp,
    color: Color = Color.Unspecified,
) = TextStyle(
    fontFamily = Ubuntu,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing,
    color = color,
)

/**
 * Ступени текста телефона — размеры макета: палец крупнее курсора, поэтому телефон на ступень
 * просторнее окна компьютера (`PointType` ПК).
 */
object PointText {

    /** Имя вещи у кольца, заголовок экрана: «Запись», «Настройки», «Мои устройства». */
    val screenTitle = ubuntu(24f, FontWeight.Bold, line = 29f)

    /** Имя приложения в шапке «Недавнего». */
    val brand = ubuntu(22f, FontWeight.Bold, line = 27f)

    /** Заголовок листа и вопроса на месте: «Убрать всё недавнее?». */
    val sheetTitle = ubuntu(20f, FontWeight.Bold, line = 25f)

    /** Имя строки: действия, объекта в списке, настройки. */
    val rowTitle = ubuntu(15f, FontWeight.Medium, line = 19f)

    /** Вторая строка о самой вещи: вид, мера, откуда. */
    val subline = ubuntu(13f, line = 17f, color = PointPalette.soft)

    /** Вторая строка в строке: обещание действия, вид и вес объекта. */
    val note = ubuntu(12.5f, line = 16f, color = PointPalette.muted)

    /** Заголовок группы — прописными, с разрядкой, рисует вызывающий. */
    val label = ubuntu(11.5f, FontWeight.Medium, line = 15f, spacing = 0.7.sp, color = PointPalette.text.copy(alpha = 0.55f))

    /** Содержимое вещи: текст читают, а не пробегают глазами. */
    val reading = ubuntu(15f, line = 23f, color = PointPalette.text.copy(alpha = 0.92f))

    /** Имя файла под вещью. */
    val mono = TextStyle(fontFamily = UbuntuMono, fontSize = 11.5.sp, lineHeight = 15.sp, color = PointPalette.muted)

    /** Время в строке «Недавнего». */
    val clock = TextStyle(fontFamily = UbuntuMono, fontSize = 12.sp, lineHeight = 16.sp, color = PointPalette.text.copy(alpha = 0.5f))

    /** Найденное значение меткой в строке «Недавнего»: номер, карта, сумма. */
    val chip = TextStyle(fontFamily = UbuntuMono, fontSize = 11.sp, lineHeight = 14.sp, color = PointPalette.text.copy(alpha = 0.72f))
}

/**
 * Роли Material — те же ступени: стандартные компоненты (кнопка, поле, меню) и экраны, которые
 * пишут ролями, говорят тем же шрифтом и размером, что строки стекла.
 */
val PointTypography = Typography(

    displaySmall = ubuntu(30f, FontWeight.Bold, line = 36f),
    headlineMedium = ubuntu(26f, FontWeight.Bold, line = 32f),
    headlineSmall = PointText.screenTitle,

    titleLarge = ubuntu(20f, FontWeight.Bold, line = 25f),
    titleMedium = ubuntu(16f, FontWeight.Medium, line = 21f),
    titleSmall = ubuntu(14f, FontWeight.Medium, line = 19f),

    bodyLarge = ubuntu(15f, line = 22f),
    bodyMedium = ubuntu(14f, line = 20f),
    bodySmall = ubuntu(12.5f, line = 16f),

    labelLarge = ubuntu(14f, FontWeight.Medium, line = 18f),
    labelMedium = ubuntu(12.5f, FontWeight.Medium, line = 16f),
    labelSmall = ubuntu(11.5f, FontWeight.Medium, line = 15f),
)
