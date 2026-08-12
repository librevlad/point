package com.point

import com.point.data.YoloByDefault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Каким режим YOLO приходит в сборке, где его ещё не трогали руками (#795).
 *
 * Решение владельца 12.08.2026 — «делаем сразу и включаем по умолчанию», при его же границе
 * из карточки: «в дебаг сборке по умолчанию всегда true». В сборках для своих (`debug`,
 * `dogfood`) человек за экраном — сам разработчик, и режим включён сразу. В релизе он
 * выключен: конституция (§11) запрещает подразумевать согласие на выход объекта наружу по
 * умолчанию — его дают руками, выбором режима.
 *
 * Тип сборки знает только `:app`, поэтому значение рождается здесь, а не в `:data`.
 */
@Module
@InstallIn(SingletonComponent::class)
object YoloDefaultModule {

    @Provides
    @YoloByDefault
    fun yoloByDefault(): Boolean = BuildConfig.BUILD_TYPE != "release"
}
