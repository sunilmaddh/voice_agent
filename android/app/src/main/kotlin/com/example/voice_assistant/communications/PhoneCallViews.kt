package com.example.voice_assistant.communications

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Native call presentation: no Flutter engine or microphone session required. */
internal class PhoneCallViews(private val context: Context) {
    val ink = Color.rgb(239, 244, 255)
    val muted = Color.rgb(172, 190, 215)
    val surface = Color.rgb(31, 48, 72)
    val green = Color.rgb(26, 119, 79)
    val red = Color.rgb(182, 47, 65)
    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun shape(color: Int, radius: Int = 24) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun text(value: String, size: Float = 16f, color: Int = ink) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    fun button(value: String, color: Int = surface, action: () -> Unit) = Button(context).apply {
        text = value; textSize = 16f; isAllCaps = false; setTextColor(ink)
        background = shape(color); minHeight = dp(64)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }
        setOnClickListener { action() }
    }
    fun identity(parent: LinearLayout, name: String, number: String?, state: String) {
        parent.addView(text(state, 15f, muted))
        parent.addView(text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", 44f).apply {
            background = shape(surface, 56)
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, dp(20), 0, dp(20))
            }
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        parent.addView(text(name, 30f).apply { setTypeface(null, Typeface.BOLD) })
        if (!number.isNullOrBlank() && number != name) parent.addView(text(number, 17f, muted))
    }
    fun row(parent: LinearLayout, vararg buttons: Button) {
        parent.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { button -> addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                setMargins(dp(4), dp(6), dp(4), dp(6))
            }) }
        })
    }
}
