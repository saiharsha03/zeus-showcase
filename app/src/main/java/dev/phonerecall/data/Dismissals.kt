package dev.phonerecall.data

import android.content.Context

/**
 * Card instances swiped away as done, in their own prefs file. Only the dismiss path writes it (the Home card
 * swipe and the Now Bar delete intent), so neither can overwrite the other through a stale HomePrefs copy.
 * Key: instance key (see dismissKey), value: epoch ms the item stops being relevant.
 */
object Dismissals {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("athena_dismissed", Context.MODE_PRIVATE)

    fun load(c: Context): Map<String, Long> = sp(c).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    /** Until `until` (24 h from `now` when unknown); expired entries are dropped on the way. */
    fun add(c: Context, instanceKey: String, until: Long?, now: Long) {
        val e = sp(c).edit()
        for ((k, v) in load(c)) if (v <= now) e.remove(k)
        e.putLong(dismissKey(instanceKey), (until ?: (now + 24 * 3_600_000L)).coerceAtLeast(now + 60_000L)).apply()
    }

    fun remove(c: Context, instanceKey: String) { sp(c).edit().remove(dismissKey(instanceKey)).apply() }

    /** Calls back with the full map whenever it changes. Keep the returned listener referenced. */
    fun watch(c: Context, onChange: (Map<String, Long>) -> Unit): android.content.SharedPreferences.OnSharedPreferenceChangeListener {
        val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onChange(load(c)) }
        sp(c).registerOnSharedPreferenceChangeListener(l)
        return l
    }
}
