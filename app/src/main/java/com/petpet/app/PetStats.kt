package com.petpet.app

import android.content.Context

/** 쓰다듬기 통계. 기기 안(SharedPreferences)에만 저장하며 외부로 전송하지 않는다. */
object PetStats {
    private const val APP_PREFIX = "app:"
    private const val UNKNOWN = "알 수 없음"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("petpet_stats", Context.MODE_PRIVATE)

    // shortcut: 런처가 노출하는 표시 이름 기준으로 집계(패키지명 아님), 같은 이름의 앱은 합쳐짐
    fun record(ctx: Context, label: String?) {
        val p = prefs(ctx)
        val key = APP_PREFIX + (label ?: UNKNOWN)
        p.edit()
            .putLong("total", p.getLong("total", 0) + 1)
            .putInt(key, p.getInt(key, 0) + 1)
            .apply()
    }

    fun total(ctx: Context): Long = prefs(ctx).getLong("total", 0)

    /** (앱 이름, 횟수) 목록, 많이 쓰다듬은 순 */
    fun apps(ctx: Context): List<Pair<String, Int>> =
        prefs(ctx).all.mapNotNull { (k, v) ->
            if (k.startsWith(APP_PREFIX) && v is Int) k.removePrefix(APP_PREFIX) to v else null
        }.sortedByDescending { it.second }
}
