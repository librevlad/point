package com.point.data

import android.content.Context
import com.point.core.flow.AtomRecognizer

/**
 * В релизе своих сетей чтения нет (#747, решение владельца «только в dogfood, пока не
 * померяем»): ни моделей, ни библиотеки вывода в сборке, ни этих 40 МБ у человека.
 *
 * Настоящая реализация лежит в наборе исходников `localOcr` и подключена к debug и dogfood.
 */
internal object LocalOcr {

    fun reader(context: Context): AtomRecognizer? = null
}
