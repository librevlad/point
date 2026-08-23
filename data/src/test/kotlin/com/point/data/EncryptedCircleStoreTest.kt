package com.point.data

import android.content.SharedPreferences
import com.point.core.flow.CircleDevice
import com.point.core.flow.CircleStore
import com.point.core.flow.DeviceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Телефон помнит последний круг (#1076): сервер молчит — список не пустеет.
 *
 * Хранилище проверяется через интерфейс [CircleStore]: экрану и ViewModel безразлично,
 * чем оно устроено внутри. `null` — круга не было никогда, и это не «в круге никого нет».
 */
class EncryptedCircleStoreTest {

    private val prefs = InMemoryPrefs()
    private fun store(): CircleStore = EncryptedCircleStore({ prefs })

    private val circle = listOf(
        CircleDevice("d1", DeviceKind.PHONE, "Pixel 8", 1_800_000_000_000L, self = true),
        CircleDevice("d2", DeviceKind.PC, "Рабочий ноутбук", 1_799_999_990_000L),
    )

    @Test fun `пока круга не было никогда — null, а не пустой список`() {
        assertNull(store().current())
    }

    @Test fun `сохранённый круг читается таким же — и после перезапуска`() = runBlocking {
        store().save(circle)

        // Новый экземпляр над теми же байтами — как после смерти процесса.
        assertEquals(circle, store().current())
    }

    @Test fun `устройство без единого контакта переживает запись без выдумывания времени`() = runBlocking {
        val silent = listOf(CircleDevice("d3", DeviceKind.PC, "Новый ПК", lastSeenMillis = null))
        store().save(silent)

        assertEquals(silent, store().current())
    }

    @Test fun `очистка возвращает к «круга не было никогда»`() = runBlocking {
        val store = store()
        store.save(circle)
        store.clear()

        assertNull(store.current())
    }

    @Test fun `испорченная запись читается как отсутствие круга, а не падение`() {
        prefs.edit().putString("devices", "не json").apply()

        assertNull(store().current())
    }

    /**
     * Пустой список — незнание круга, а не круг без устройств: в круге всегда есть хотя бы
     * это устройство (#1076). Правило одно на весь контракт, поэтому и проверка одна на обе
     * реализации: шифрованная молча ничего не делала, оперативная затирала память, и на
     * одном и том же вызове проверки видели одно поведение, а телефон человека — другое.
     */
    private fun bothStores(): List<Pair<String, CircleStore>> = listOf(
        "шифрованная память круга" to EncryptedCircleStore({ InMemoryPrefs() }),
        "оперативная память круга" to com.point.core.flow.InMemoryCircleStore(),
    )

    @Test fun `пустой список не стирает известный круг — ни в одной реализации`() = runBlocking {
        bothStores().forEach { (whose, store) ->
            store.save(circle)
            store.save(emptyList())

            assertEquals(whose, listOf("d1", "d2"), store.current()?.map { it.id })
        }
    }

    @Test fun `пустой список не выдумывает круг там, где его не было — ни в одной реализации`() = runBlocking {
        bothStores().forEach { (whose, store) ->
            store.save(emptyList())

            assertNull(whose, store.current())
        }
    }
}

/** Память вместо шифрованных настроек: контракт тот же — пары ключ-значение. */
private class InMemoryPrefs : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val staged = mutableMapOf<String, Any?>()
        private var wipe = false

        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            staged[key] = value
            return this
        }

        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, value: MutableSet<String>?) = put(key, value)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            wipe = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (wipe) values.clear()
            staged.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
        }
    }
}
