package dev.phonerecall.wear

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Log a weight from the wrist: starts at the last one, nudged by 0.1 kg, sent to the phone on Log.
 * The phone keeps it in Iaso and writes it to Health Connect. The watch keeps only the last number.
 */
class WeightActivity : Activity() {
    private var kg = 72.6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("weight", MODE_PRIVATE)
        // The watch used to keep pounds; an old number is carried over once as kilograms.
        kg = if (sp.contains("kg")) sp.getFloat("kg", 70f).toDouble() else if (sp.contains("lb")) sp.getFloat("lb", 160f) * 0.45359237 else 70.0
        val value = TextView(this).apply { textSize = 30f; setTextColor(Color.rgb(0xF2, 0xEE, 0xE6)); gravity = Gravity.CENTER }
        fun show() { value.text = "${(kg * 10).roundToInt() / 10.0} kg" }
        show()
        fun nudge(d: Double) = Button(this).apply {
            text = if (d < 0) "-" else "+"
            setOnClickListener { kg = (((kg + d) * 10).roundToInt() / 10.0).coerceIn(25.0, 300.0); show() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; addView(nudge(-0.1)); addView(nudge(0.1)) }
        val log = Button(this).apply {
            text = "Log"
            setOnClickListener {
                sp.edit().putFloat("kg", kg.toFloat()).remove("lb").apply()
                Phone.send(this@WeightActivity, WearPaths.WEIGHT, kg.toString())
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(0x1B, 0x1A, 0x17))
            addView(value); addView(row); addView(log)
        })
    }
}
