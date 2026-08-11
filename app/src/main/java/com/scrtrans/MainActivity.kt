package com.scrtrans

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * Plain android.app.Activity, views assembled in code. No AndroidX, no XML layout —
 * without an IDE preview the XML buys us nothing and costs a dependency tree.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var appList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TargetApps.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "화면 번역"
            textSize = 26f
            setTextColor(Color.rgb(24, 24, 28))
        })

        root.addView(TextView(this).apply {
            text = getString(R.string.service_description)
            textSize = 14f
            setTextColor(Color.rgb(90, 90, 96))
            setPadding(0, 24, 0, 40)
        })

        status = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 40)
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

        // Rebuilt in onResume rather than filled once: the scout adds rows while the
        // user is off in another app, and coming back here is when they would look.
        appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 56, 0, 0)
        }
        root.addView(appList)

        root.gravity = Gravity.TOP

        // targetSdk 35+ draws the window edge to edge, so the status bar would otherwise sit
        // on top of the title. Inset the content instead of guessing a top padding.
        val screen = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = if (on) "● 켜짐 — 번역이 동작합니다" else "○ 꺼짐 — 접근성 설정에서 켜주세요"
        status.setTextColor(if (on) Color.rgb(20, 130, 70) else Color.rgb(190, 60, 60))
        buildAppList()
    }

    /**
     * One row per known app with a switch on it. Built-ins sort first and start on;
     * everything the scout found sorts after and starts off, so leaving a newly found
     * app alone is the same as declining it — and its row is still here tomorrow.
     */
    private fun buildAppList() {
        appList.removeAllViews()

        appList.addView(sectionLabel("앱별 번역"))
        appList.addView(TextView(this).apply {
            text = "켜 둔 앱의 화면에만 번역을 겹쳐 표시합니다."
            textSize = 13f
            setTextColor(Color.rgb(140, 140, 148))
            setPadding(0, 0, 0, 16)
        })

        val known = TargetApps.known().sortedWith(
            compareBy({ it !in TargetApps.BUILT_IN }, { it }),
        )
        for (pkg in known) appList.addView(appRow(pkg))

        // Counts the rows on screen, not just the scout's finds. Counting only the
        // latter left the number disagreeing with the list in front of it, and it went
        // to zero for anyone who does not have the built-in app installed.
        val listed = TargetApps.known().size
        appList.addView(TextView(this).apply {
            text = if (listed == 0) {
                "일본어 앱을 사용하면 여기에 자동으로 추가됩니다."
            } else {
                "번역할 수 있는 앱 ${listed}개. 다른 일본어 앱을 사용하면 여기에 계속 추가됩니다."
            }
            textSize = 12f
            setTextColor(Color.rgb(160, 160, 168))
            setPadding(0, 24, 0, 0)
        })
    }

    private fun appRow(pkg: String): LinearLayout {
        val installed = isInstalled(pkg)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = if (installed) appLabel(this@MainActivity, pkg) else "${appLabel(this@MainActivity, pkg)} (미설치)"
                    textSize = 15f
                    setTextColor(
                        if (installed) Color.rgb(24, 24, 28) else Color.rgb(170, 170, 176)
                    )
                })
                addView(TextView(this@MainActivity).apply {
                    text = pkg
                    textSize = 11f
                    setTextColor(Color.rgb(160, 160, 168))
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        row.addView(Switch(this).apply {
            // Checked before the listener is attached, or restoring state would read as
            // a tap and write back what it just read.
            isChecked = TargetApps.isEnabled(pkg)
            setOnCheckedChangeListener { _, on -> TargetApps.setEnabled(pkg, on) }
        })

        return row
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.rgb(24, 24, 28))
        setPadding(0, 0, 0, 8)
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
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
