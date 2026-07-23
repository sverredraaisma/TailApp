package com.tailapp.testutil

import android.content.SharedPreferences

/**
 * A minimal in-memory [SharedPreferences], for view models (like
 * `BeatLightViewModel`) that persist small bits of calibration state.
 *
 * `android.content.SharedPreferences` is just an interface — implementing it
 * directly avoids pulling Robolectric or a mocking framework into a JVM-only
 * test suite for what is, here, a handful of scalar reads and writes.
 * [apply] and [commit] both write synchronously, which is all a single-threaded
 * test needs.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = values }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor =
            apply { if (key != null) pending[key] = value }

        override fun remove(key: String?): SharedPreferences.Editor =
            apply { if (key != null) removals.add(key) }

        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }

        override fun commit(): Boolean {
            applyPending()
            return true
        }

        override fun apply() {
            applyPending()
        }

        private fun applyPending() {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(pending)
        }
    }
}
