package com.petitpoint.vision

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Ecran de intrare pentru Petit Point Vision.
 *
 * Păstrăm separat modul stabil pentru proiectul de 16 pagini și modul experimental LIVE,
 * unde sunt deja conectate trackingul pânzei, detectorul de progres și ghidarea acului.
 */
class LauncherActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(42), dp(24), dp(32))
            setBackgroundColor(Color.rgb(14, 14, 16))
        }

        root.addView(TextView(this).apply {
            text = "Petit Point Vision"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }, fullWidthWrap())

        root.addView(TextView(this).apply {
            text = "Alege modul de lucru"
            setTextColor(Color.LTGRAY)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(28))
        }, fullWidthWrap())

        root.addView(modeButton(
            title = "GOBLEN 16 PAGINI",
            subtitle = "Caută cartela 1–59, găsește simbolurile și suprapune-le pe pânză."
        ) {
            startActivity(Intent(this, GoblenActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = "Modul de lucru existent, păstrat neschimbat."
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(dp(8), dp(8), dp(8), dp(24))
        }, fullWidthWrap())

        root.addView(modeButton(
            title = "VERIFICARE LIVE",
            subtitle = "Camera urmărește pânza, detectează schimbările de cusătură și încearcă să ghideze acul."
        ) {
            startActivity(Intent(this, MainActivity::class.java))
        })

        root.addView(TextView(this).apply {
            text = "LIVE este experimental: pentru precizie, fixează telefonul, luminează uniform pânza și fă zoom până ochiurile sunt clare."
            setTextColor(Color.rgb(190, 220, 220))
            textSize = 13f
            setPadding(dp(8), dp(12), dp(8), 0)
        }, fullWidthWrap())

        setContentView(root)
    }

    private fun modeButton(title: String, subtitle: String, action: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.rgb(35, 35, 40))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }

            addView(Button(context).apply {
                text = title
                textSize = 17f
                setOnClickListener { action() }
            }, fullWidthWrap())

            addView(TextView(context).apply {
                text = subtitle
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(dp(4), dp(10), dp(4), 0)
            }, fullWidthWrap())
        }
    }

    private fun fullWidthWrap(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
