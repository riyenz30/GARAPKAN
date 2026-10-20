package com.dualspace.clone.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object RianPopup {

    private const val PREF_NAME = "rian_popup_prefs"
    private const val KEY_LAST_SHOWN = "last_popup_time"
    private const val INTERVAL_MILLIS = 24 * 60 * 60 * 1000L // 1x sehari (24 jam)

    fun show(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastShown = prefs.getLong(KEY_LAST_SHOWN, 0L)
        val now = System.currentTimeMillis()

        if (now - lastShown < INTERVAL_MILLIS) {
            Toast.makeText(activity, "⚡ Selamat datang di GARAPKAN by Rianshop ⚡", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.setCancelable(true)

            val dp = { value: Int ->
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    value.toFloat(),
                    activity.resources.displayMetrics
                ).toInt()
            }

            // Card Container
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#181C24")) // Dark Slate
                    cornerRadius = dp(24).toFloat()
                    setStroke(dp(1), Color.parseColor("#2E384D"))
                }
                layoutParams = ViewGroup.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            // Pill Badge
            val badge = TextView(activity).apply {
                text = "✦ RIANSHOP CLONER ✦"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(4), dp(12), dp(4))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#0F2942"))
                    cornerRadius = dp(12).toFloat()
                }
            }
            root.addView(badge)

            // Title
            val title = TextView(activity).apply {
                text = "GARAPKAN"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(14), 0, dp(4))
            }
            root.addView(title)

            // Subtitle
            val subtitle = TextView(activity).apply {
                text = "by RIANSHOP"
                setTextColor(Color.parseColor("#38BDF8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 0, 0, dp(12))
            }
            root.addView(subtitle)

            // Description
            val desc = TextView(activity).apply {
                text = "Aplikasi Clone Multi-Instance tanpa batas & bebas iklan.\nBergabung dengan komunitas kami untuk info & update terbaru."
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setLineSpacing(dp(3).toFloat(), 1.0f)
                setPadding(0, 0, 0, dp(20))
            }
            root.addView(desc)

            // Channel Telegram Button
            val btnChannel = Button(activity).apply {
                text = "📢 Channel Telegram"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB")) // Modern Blue
                    cornerRadius = dp(14).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                ).apply {
                    bottomMargin = dp(10)
                }
                setOnClickListener {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/rianxhurayra")))
                }
            }
            root.addView(btnChannel)

            // Group Telegram Button
            val btnGroup = Button(activity).apply {
                text = "💬 Grup Telegram"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                isAllCaps = false
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#1E293B"))
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(46)
                ).apply {
                    bottomMargin = dp(12)
                }
                setOnClickListener {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ajasendiri")))
                }
            }
            root.addView(btnGroup)

            // Hide / Dismiss Button
            val btnHide = TextView(activity).apply {
                text = "Tutup"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    dialog.dismiss()
                }
            }
            root.addView(btnHide)

            dialog.setContentView(root)
            dialog.setOnDismissListener {
                prefs.edit().putLong(KEY_LAST_SHOWN, System.currentTimeMillis()).apply()
                Toast.makeText(activity, "⚡ Selamat datang di GARAPKAN by Rianshop ⚡", Toast.LENGTH_SHORT).show()
            }
            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
