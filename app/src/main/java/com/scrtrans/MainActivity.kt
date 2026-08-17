package com.scrtrans

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
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
    private lateinit var setupSteps: LinearLayout

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

        // The button only opens the accessibility list; the switch is two screens further
        // in, under a label the user has no reason to recognise. Without the path spelled
        // out, the button looks like it did nothing.
        setupSteps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24)
            addView(step(1, "아래 버튼으로 접근성 설정 열기"))
            addView(step(2, "설치된 앱"))
            addView(step(3, "${getString(R.string.service_label)} › 사용 켜기"))
        }
        root.addView(setupSteps)

        root.addView(Button(this).apply {
            text = "접근성 설정 열기"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        root.addView(divider())
        root.addView(sectionLabel("번역 품질"))
        root.addView(sectionNote("짧은 라벨은 그대로, 본문은 몇 초 뒤 품질을 높인 번역으로 교체합니다."))
        root.addView(llmRow())

        // Rebuilt in onResume rather than filled once: the scout adds rows while the
        // user is off in another app, and coming back here is when they would look.
        appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        // Setup instructions are for people who have not done the setup.
        setupSteps.visibility = if (on) View.GONE else View.VISIBLE
        buildAppList()
    }

    /**
     * One row per known app with a switch on it. Built-ins sort first and start on;
     * everything the scout found sorts after and starts off, so leaving a newly found
     * app alone is the same as declining it — and its row is still here tomorrow.
     */
    private fun buildAppList() {
        appList.removeAllViews()

        appList.addView(divider())
        appList.addView(sectionLabel("앱별 번역"))
        appList.addView(sectionNote("켜 둔 앱의 화면에만 번역을 겹쳐 표시합니다."))

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

    /**
     * The LLM switch, with the model's whereabouts under it. The file is the thing most
     * likely to be missing, and a switch that silently does nothing is worse than one
     * that says why.
     */
    private fun llmRow(): LinearLayout {
        val model = LlmEngine.findModel(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }

        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "LLM 재번역"
                    textSize = 15f
                    setTextColor(
                        if (model != null) Color.rgb(24, 24, 28) else Color.rgb(170, 170, 176)
                    )
                })
                // Only the model's whereabouts, and only when it is missing — what the
                // switch does is the section's line to say, once.
                if (model == null) {
                    addView(TextView(this@MainActivity).apply {
                        text = "모델 없음 — files/model.litertlm 필요"
                        textSize = 11f
                        setTextColor(Color.rgb(160, 160, 168))
                    })
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        row.addView(Switch(this).apply {
            isEnabled = model != null
            isChecked = LlmSettings.enabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> LlmSettings.setEnabled(this@MainActivity, on) }
        })

        return row
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.rgb(24, 24, 28))
        setPadding(0, 0, 0, 8)
    }

    /** One numbered line of the setup path. The number is a column so the text lines up. */
    private fun step(n: Int, text: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 4, 0, 4)
        addView(TextView(this@MainActivity).apply {
            this.text = "$n"
            textSize = 13f
            setTextColor(Color.rgb(150, 150, 158))
            width = 44
        })
        addView(TextView(this@MainActivity).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.rgb(90, 90, 96))
        })
    }

    /** The grey line under a section label. Same voice as [sectionLabel], one size down. */
    private fun sectionNote(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(140, 140, 148))
        setPadding(0, 0, 0, 16)
    }

    /**
     * A rule above each section heading. Spacing alone was not enough to tell the LLM
     * switch apart from the app list below it — the two read as one list of switches,
     * and turning translation off for an app looks like it belongs with the LLM setting.
     */
    private fun divider() = View(this).apply {
        setBackgroundColor(Color.rgb(228, 228, 234))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            2,
        ).apply {
            topMargin = 56
            bottomMargin = 28
        }
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
