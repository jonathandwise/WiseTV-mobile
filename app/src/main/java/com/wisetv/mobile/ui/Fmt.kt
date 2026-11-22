package com.wisetv.mobile.ui

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Fmt {
    private var is24 = false

    fun init(ctx: Context) {
        is24 = DateFormat.is24HourFormat(ctx)
    }

    private val t12 = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val t24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val shortDay = SimpleDateFormat("EEE d", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

    fun time(ms: Long): String = synchronized(this) { (if (is24) t24 else t12).format(Date(ms)) }
    fun range(start: Long, stop: Long) = "${time(start)} – ${time(stop)}"
    fun date(ms: Long): String = synchronized(this) { dateFmt.format(Date(ms)) }

    fun day(ms: Long): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val diff = dayIndex(c) - dayIndex(today)
        return when (diff) {
            0 -> "Today"
            1 -> "Tomorrow"
            -1 -> "Yesterday"
            else -> synchronized(this) { dayFmt.format(Date(ms)) }
        }
    }

    fun shortDay(ms: Long): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return if (dayIndex(c) == dayIndex(today)) "Today" else synchronized(this) { shortDay.format(Date(ms)) }
    }

    private fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)

    fun duration(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m} min"
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(bytes / 1024.0)
    }
}
