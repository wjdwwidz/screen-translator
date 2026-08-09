package com.scrtrans

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Plain android.app.Activity, views assembled in code. No AndroidX, no XML layout —
 * without an IDE preview the XML buys us nothing and costs a dependency tree.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView

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

        root.addView(TextView(this).apply {
            text = "핫페퍼 뷰티(${TranslatorService.TARGET_PACKAGE})의 일본어 화면 위에 " +
                "한국어 번역을 겹쳐 표시합니다."
            textSize = 14f
            setTextColor(Color.rgb(90, 90, 96))
            setPadding(0, 24, 0, 48)
        })

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 48)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.gravity = Gravity.TOP
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "● 켜짐 — 번역이 동작합니다" else "○ 꺼짐 — 접근성 설정에서 켜주세요"
        status.setTextColor(if (on) Color.rgb(20, 130, 70) else Color.rgb(190, 60, 60))
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${TranslatorService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (entry in splitter) {
            if (entry.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
