package com.point

import com.point.data.YoloByDefault
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Каким режим YOLO приходит в сборке, где его ещё не трогали руками (#795, #1265).
 *
 * Решение владельца 12.08.2026 — «делаем сразу и включаем по умолчанию», при его же границе
 * из карточки: «в дебаг сборке по умолчанию всегда true». Отладочная сборка живёт на машине
 * разработчика, и человек за экраном — он сам.
 *
 * Дальше этого умолчание не идёт (решение владельца 23.08.2026, #1265). `dogfood` — та самая
 * сборка, которую раздаёт публичный лендинг: посылка «сборка для своих» на ней не держится,
 * и ведёт себя она как релиз — спрашивает согласие. Конституция (§11) запрещает
 * подразумевать согласие на выход объекта наружу по умолчанию: его дают руками, выбором
 * режима в настройках.
 *
 * Тип сборки знает только `:app`, поэтому значение рождается здесь, а не в `:data`.
 */
@Module
@InstallIn(SingletonComponent::class)
object YoloDefaultModule {

    @Provides
    @YoloByDefault
    fun yoloByDefault(): Boolean = BuildConfig.DEBUG
}
