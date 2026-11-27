package ru.drdroid78.sberboom.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Диагностический журнал: кольцевой буфер в памяти + вывод в logcat. */
object DiagLog {
    private const val TAG = "SberBoomAudio"
    private const val MAX_LINES = 3000

    data class Entry(val time: Long, val level: String, val message: String) {
        fun format(): String = "${formatTime(time)} [$level] $message"
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun formatTime(time: Long): String = synchronized(timeFormat) {
        timeFormat.format(Date(time))
    }

    private val buffer = ArrayDeque<Entry>(MAX_LINES)
    private val _lines = MutableStateFlow<List<Entry>>(emptyList())
    val lines: StateFlow<List<Entry>> = _lines.asStateFlow()

    @Synchronized
    private fun add(level: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        buffer.addLast(entry)
        while (buffer.size > MAX_LINES) buffer.removeFirst()
        _lines.value = buffer.toList()
        when (level) {
            "E" -> Log.e(TAG, message)
            "W" -> Log.w(TAG, message)
            "D" -> Log.d(TAG, message)
            else -> Log.i(TAG, message)
        }
    }

    fun i(message: String) = add("I", message)
    fun w(message: String) = add("W", message)
    fun e(message: String, error: Throwable? = null) =
        add("E", if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message)

    fun d(message: String) = add("D", message)

    fun dump(): String = synchronized(this) {
        buffer.joinToString("\n") { it.format() }
    }

    fun clear() {
        synchronized(this) {
            buffer.clear()
            _lines.value = emptyList()
        }
    }
}
