package ai.closepaw.auth

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal in-memory [SharedPreferences] for JVM tests. Only the String-keyed
 * read/write/remove/contains paths are implemented — enough for [AuthStore].
 * Listeners and getters for non-String types are no-ops / not supported.
 */
internal class FakeSharedPreferences : SharedPreferences {
    private val map = ConcurrentHashMap<String, String>()

    override fun getAll(): Map<String, *> = map.toMap()
    override fun getString(key: String, defValue: String?): String? = map[key] ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = defValue
    override fun getLong(key: String, defValue: Long): Long = defValue
    override fun getFloat(key: String, defValue: Float): Float = defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = defValue
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, String?>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pending[key] = null
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun apply() { commit() }

        override fun commit(): Boolean {
            if (clearAll) map.clear()
            for ((k, v) in pending) {
                if (v == null) map.remove(k) else map[k] = v
            }
            return true
        }

        override fun putStringSet(key: String, values: Set<String>?) = this
        override fun putInt(key: String, value: Int) = this
        override fun putLong(key: String, value: Long) = this
        override fun putFloat(key: String, value: Float) = this
        override fun putBoolean(key: String, value: Boolean) = this
    }
}
