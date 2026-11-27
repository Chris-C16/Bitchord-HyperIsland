package com.bitchord.island

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CrashReportActivity : Activity() {

    companion object {
        const val EXTRA_STACKTRACE = "extra_crash_stacktrace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val stacktrace = intent?.getStringExtra(EXTRA_STACKTRACE)
            ?.takeIf { it.isNotBlank() }
            ?: "Nessuno stacktrace disponibile."

        val dp = { v: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0D14"))
            setPadding(dp(20f), dp(48f), dp(20f), dp(28f))
        }

        val titleView = TextView(this).apply {
            text = "DynamicLyrics si è arrestato"
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        root.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Copia questo log per diagnosticare esattamente l'errore:"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 13f
            setPadding(0, dp(6f), 0, dp(14f))
        }
        root.addView(subtitleView)

        val logCardBg = GradientDrawable().apply {
            setColor(Color.parseColor("#141824"))
            cornerRadius = dp(14f).toFloat()
            setStroke(dp(1f), Color.parseColor("#33F87171"))
        }

        val logTextView = TextView(this).apply {
            text = stacktrace
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
        }

        val scrollView = ScrollView(this).apply {
            background = logCardBg
            addView(
                logTextView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val scrollParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        root.addView(scrollView, scrollParams)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(16f), 0, 0)
        }

        val copyBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#2563EB"))
            cornerRadius = dp(24f).toFloat()
        }
        val copyBtn = Button(this).apply {
            text = "Copia log"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            background = copyBtnBg
            setOnClickListener {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("DynamicLyrics Crash Log", stacktrace))
                    Toast.makeText(this@CrashReportActivity, "Log copiato negli appunti", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(48f), 1f).apply {
            marginEnd = dp(10f)
        })

        val restartBtnBg = GradientDrawable().apply {
            setColor(Color.parseColor("#1E293B"))
            cornerRadius = dp(24f).toFloat()
            setStroke(dp(1f), Color.parseColor("#475569"))
        }
        val restartBtn = Button(this).apply {
            text = "Riapri app"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = restartBtnBg
            setOnClickListener {
                try {
                    val launch = Intent(this@CrashReportActivity, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    startActivity(launch)
                } catch (_: Throwable) {
                }
                finishAndRemoveTask()
            }
        }
        btnRow.addView(restartBtn, LinearLayout.LayoutParams(0, dp(48f), 1f))

        root.addView(btnRow)
        setContentView(root)
    }
}
