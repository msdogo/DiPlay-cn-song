package com.shilapi.xcertplay.backup

import android.content.Context
import android.content.SharedPreferences.Editor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The document a settings file carries.
 *
 * The backup page and a settings file share one format, one whitelist for the common settings and one type
 * check, so a file restores at least what the page restores. A file may carry more: it is written into Downloads
 * and read back by the driver, while the page is shown to whatever browser opens its address. The settings that
 * only a file carries live in [SECTION], so the page's own document never changes.
 */
object SettingsPortableCodec {
    const val FORMAT = SettingsBackupCodec.FORMAT
    const val VERSION = SettingsBackupCodec.VERSION

    /** Where the file-only settings travel. A page export has no such member, and an older DiPlay ignores it. */
    const val SECTION = "portable"

    private const val MAX_DOCUMENT_BYTES = 512 * 1024

    private enum class KeyType { BOOLEAN, INT, STRING }

    private sealed interface Policy {
        fun includes(key: String): Boolean
        fun typeOf(key: String): KeyType?

        data class Allow(val keys: Set<String>, val types: Map<String, KeyType> = emptyMap()) : Policy {
            override fun includes(key: String) = key in keys
            override fun typeOf(key: String) = types[key]
        }
    }

    /** The two hotspots are a name and a passphrase the driver typed, not a credential DiPlay was given. */
    private val AIRPLAY_EXTRA_KEYS = setOf(
        "manual_hotspot_ssid", "manual_hotspot_passphrase",
        "debug_logs_enabled", "manufacturer", "model", "oem_label",
        "call_at_call_rate", "media_over_bluetooth", "launcher_map_sharing",
        "launcher_returns_to_carplay", "swipe_opens_full_settings", "update_channel",
    )
    private val AIRPLAY_EXTRA_TYPES = mapOf(
        "manual_hotspot_ssid" to KeyType.STRING,
        "manual_hotspot_passphrase" to KeyType.STRING,
        "manufacturer" to KeyType.STRING,
        "model" to KeyType.STRING,
        "oem_label" to KeyType.STRING,
        "update_channel" to KeyType.INT,
    )

    /** The turn side view, which the driver tunes window by window. Nothing here is bound to one car. */
    private val SIDE_VIEW_KEYS = setOf(
        "enabled", "threshold_kmh", "width_pct", "rotate", "mirror_left", "mirror_right",
        "flip_vertical", "swap_sides", "left_channel", "right_channel", "bsd_warn", "both_on_main",
        "right_x", "right_y", "left_x", "left_y",
    )
    private val SIDE_VIEW_TYPES = mapOf(
        "threshold_kmh" to KeyType.INT, "width_pct" to KeyType.INT,
        "left_channel" to KeyType.INT, "right_channel" to KeyType.INT,
        "right_x" to KeyType.INT, "right_y" to KeyType.INT,
        "left_x" to KeyType.INT, "left_y" to KeyType.INT,
    )

    private val EXTRA_FILES = mapOf(
        "xcertplay_airplay" to Policy.Allow(AIRPLAY_EXTRA_KEYS, AIRPLAY_EXTRA_TYPES),
        "side_view" to Policy.Allow(SIDE_VIEW_KEYS, SIDE_VIEW_TYPES),
        // "authorized" is an accessibility grant this unit gave, so only the driver's choice travels.
        "diplay_navigation_wheel" to Policy.Allow(
            setOf("enabled"),
            mapOf("enabled" to KeyType.BOOLEAN),
        ),
        // Radio memory only: a channel this unit once created a group on, never a key or an address.
        "carplay_wifi_p2p_success" to Policy.Allow(setOf("confirmed"), mapOf("confirmed" to KeyType.STRING)),
    )

    /** Everything the backup page would write, plus [SECTION]. Both halves are read back by the same checks. */
    fun export(context: Context, appVersion: String): ByteArray {
        val root = JSONObject(String(SettingsBackupCodec.export(context, appVersion), Charsets.UTF_8))
        val files = JSONObject()
        for ((name, policy) in EXTRA_FILES) {
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                .filterKeys(policy::includes)
                .filterValues { it is Boolean || it is Int || it is Long || it is Float || it is String || it is Set<*> }
            if (values.isEmpty()) continue
            val file = JSONObject()
            for ((key, value) in values) file.put(key, jsonValue(value as Any))
            files.put(name, file)
        }
        root.put(SECTION, files)
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    /** Runs the exact validation an import runs, over both halves, without persisting anything. */
    fun preview(context: Context, document: ByteArray): SettingsImportOutcome = read(context, document, apply = false)

    fun import(context: Context, document: ByteArray): SettingsImportOutcome = read(context, document, apply = true)

    private fun read(context: Context, document: ByteArray, apply: Boolean): SettingsImportOutcome {
        if (document.size > MAX_DOCUMENT_BYTES) return SettingsImportOutcome(0, 0, invalid = true)
        val root = runCatching { JSONObject(document.toString(Charsets.UTF_8)) }.getOrNull()
            ?: return SettingsImportOutcome(0, 0, invalid = true)
        val base = if (apply) SettingsBackupCodec.import(context, document)
        else SettingsBackupCodec.preview(context, document)
        if (base.invalid) return base
        val files = root.optJSONObject(SECTION) ?: return base
        var applied = base.applied
        var skipped = base.skipped
        for (name in files.keys()) {
            val policy = EXTRA_FILES[name]
            val file = files.optJSONObject(name)
            if (policy == null || file == null) {
                skipped += files.optJSONObject(name)?.length() ?: 1
                continue
            }
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val current = prefs.all
            val editor = prefs.edit()
            for (key in file.keys()) {
                val value = file.opt(key)
                if (!policy.includes(key) || value === JSONObject.NULL) {
                    skipped++
                    continue
                }
                val stored = runCatching { store(editor, policy, current, key, value) }.getOrDefault(false)
                if (stored) applied++ else skipped++
            }
            if (apply) editor.apply()
        }
        return SettingsImportOutcome(applied, skipped, invalid = false)
    }

    private fun store(
        editor: Editor,
        policy: Policy,
        current: Map<String, *>,
        key: String,
        value: Any?,
    ): Boolean = when (val declared = policy.typeOf(key)) {
        KeyType.BOOLEAN -> if (value is Boolean) { editor.putBoolean(key, value); true } else false
        KeyType.INT -> integral(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
            ?.let { editor.putInt(key, it.toInt()); true } ?: false
        KeyType.STRING -> if (value is String) { editor.putString(key, value); true } else false
        null -> when (val target = current[key]) {
            is Boolean -> if (value is Boolean) { editor.putBoolean(key, value); true } else false
            is Int -> integral(value)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                ?.let { editor.putInt(key, it.toInt()); true } ?: false
            is Long -> integral(value)?.let { editor.putLong(key, it); true } ?: false
            is Float -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
            is String -> if (value is String) { editor.putString(key, value); true } else false
            is Set<*> -> stringSet(value)?.let { editor.putStringSet(key, it); true } ?: false
            null -> inferred(editor, key, value)
            else -> false
        }
    }

    private fun inferred(editor: Editor, key: String, value: Any?): Boolean = when (value) {
        is Boolean -> { editor.putBoolean(key, value); true }
        is String -> { editor.putString(key, value); true }
        else -> {
            val integral = integral(value)
            when {
                integral != null && integral in Int.MIN_VALUE..Int.MAX_VALUE -> { editor.putInt(key, integral.toInt()); true }
                integral != null -> { editor.putLong(key, integral); true }
                else -> decimal(value)?.let { editor.putFloat(key, it); true } ?: false
            }
        }
    }

    private fun integral(value: Any?): Long? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double % 1.0 != 0.0 || double < Long.MIN_VALUE || double > Long.MAX_VALUE) return null
        return double.toLong()
    }

    private fun decimal(value: Any?): Float? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        return if (!double.isFinite()) null else double.toFloat().takeIf { it.isFinite() }
    }

    private fun stringSet(value: Any?): Set<String>? {
        val array = value as? JSONArray ?: return null
        val result = LinkedHashSet<String>()
        for (index in 0 until array.length()) {
            val element = array.opt(index) as? String ?: return null
            result.add(element)
        }
        return result
    }

    private fun jsonValue(value: Any): Any = when (value) {
        is Set<*> -> JSONArray().apply { value.forEach { put(it.toString()) } }
        is Float -> value.toDouble()
        else -> value
    }
}
