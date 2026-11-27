package ru.drdroid78.sberboom.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import ru.drdroid78.sberboom.R
import ru.drdroid78.sberboom.util.DiagLog

class LogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val text = findViewById<TextView>(R.id.logText)
        val scroll = findViewById<ScrollView>(R.id.scroll)

        lifecycleScope.launch {
            DiagLog.lines.collect { lines ->
                text.text = if (lines.isEmpty()) getString(R.string.log_empty)
                else lines.joinToString("\n") { it.format() }
                scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        findViewById<MaterialButton>(R.id.copyButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SberBoom log", DiagLog.dump()))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.clearButton).setOnClickListener {
            DiagLog.clear()
        }
    }
}
