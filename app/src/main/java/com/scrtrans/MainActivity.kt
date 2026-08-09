package com.scrtrans

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Plain android.app.Activity, views assembled in code. No AndroidX, no XML layout —
 * without an IDE preview the XML buys us nothing and costs a dependency tree.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "화면 번역"
            textSize = 26f
            setTextColor(Color.rgb(24, 24, 28))
        })
        setContentView(root)
    }
}
