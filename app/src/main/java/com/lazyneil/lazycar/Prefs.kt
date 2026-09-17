package com.lazyneil.lazycar

import android.content.Context

/** Thin SharedPreferences wrapper. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lazycar", Context.MODE_PRIVATE)
    private fun s(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var playerPkg: String get() = s("player"); set(v) = put("player", v)
    var mac1: String get() = s("mac1"); set(v) = put("mac1", v)
    var mac2: String get() = s("mac2"); set(v) = put("mac2", v)
    var mac3: String get() = s("mac3"); set(v) = put("mac3", v)
    var name1: String get() = s("name1"); set(v) = put("name1", v)
    var name2: String get() = s("name2"); set(v) = put("name2", v)
    var name3: String get() = s("name3"); set(v) = put("name3", v)
    var headunitIp: String get() = s("ip"); set(v) = put("ip", v)
    private fun b(k: String, def: Boolean) = sp.getBoolean(k, def)
    private fun putB(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var headunit: Boolean get() = b("headunit", false); set(v) = putB("headunit", v)
    var dualAudio: Boolean get() = b("dual", true); set(v) = putB("dual", v)
    var amoled: Boolean get() = b("amoled", true); set(v) = putB("amoled", v)
}
