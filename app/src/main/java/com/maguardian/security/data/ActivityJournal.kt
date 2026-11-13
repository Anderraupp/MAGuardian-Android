package com.maguardian.security.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.util.Calendar

/**
 * A separate, append-only record of real events. Legacy counters and their reset
 * rules are deliberately untouched. No historical executions are inferred.
 */
object ActivityJournal {
    const val UPDATED = "com.maguardian.security.ACTIVITY_UPDATED"
    data class Event(val id: String, val kind: String, val time: Long, val name: String, val source: String)
    data class Daily(val found: Int, val removed: Int, val scans: Int, val lastScan: Long)
    private class Store(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "guardian_activity.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, kind TEXT NOT NULL, time INTEGER NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL)")
            db.execSQL("CREATE INDEX event_time ON events(time)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    @Volatile private var store: Store? = null
    private fun db(ctx: Context): SQLiteDatabase {
        val helper = store ?: synchronized(this) { store ?: Store(ctx).also { store = it } }
        return helper.writableDatabase
    }
    fun record(ctx: Context, id: String, kind: String, time: Long, name: String = "", source: String = "") {
        if (time <= 0) return
        try {
            val inserted = db(ctx).insertWithOnConflict("events", null, ContentValues().apply {
                put("id", id); put("kind", kind); put("time", time); put("name", name); put("source", source)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            if (inserted != -1L) ctx.sendBroadcast(android.content.Intent(UPDATED).setPackage(ctx.packageName))
        } catch (e: Exception) {
            // Recording failures must never interrupt protection or a confirmed removal.
            Log.e("GuardianActivity", "Não foi possível registrar um evento no histórico local.", e)
            ctx.getSharedPreferences("guardian_activity_health", 0).edit().putBoolean("incomplete", true).apply()
        }
    }
    fun incomplete(ctx: Context) = ctx.getSharedPreferences("guardian_activity_health", 0).getBoolean("incomplete", false)
    fun daily(ctx: Context, now: Long = System.currentTimeMillis()): Daily {
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        val counts = mutableMapOf<String, Int>()
        db(ctx).rawQuery("SELECT kind, COUNT(*) FROM events WHERE time >= ? AND time < ? GROUP BY kind",
            arrayOf(start.timeInMillis.toString(), end.timeInMillis.toString())).use {
            while (it.moveToNext()) counts[it.getString(0)] = it.getInt(1)
        }
        val last = db(ctx).rawQuery("SELECT MAX(time) FROM events WHERE kind = 'scan'", null).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else 0L
        }
        return Daily(counts["found"] ?: 0, counts["removed"] ?: 0, counts["scan"] ?: 0, last)
    }
    fun page(ctx: Context, offset: Int = 0): List<Event> {
        val result = ArrayList<Event>()
        db(ctx).query("events", null, null, null, null, null, "time DESC, id DESC", "$offset,100").use {
            while (it.moveToNext()) result += Event(it.getString(0), it.getString(1), it.getLong(2), it.getString(3), it.getString(4))
        }
        return result
    }
}
