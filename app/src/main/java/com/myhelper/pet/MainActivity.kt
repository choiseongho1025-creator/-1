package com.myhelper.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var titleView: TextView
    private lateinit var stateView: TextView
    private lateinit var warnView: TextView
    private lateinit var sizeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Cfg.prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(56), dp(24), dp(24))
        }
        titleView = tv("", 26f, true).apply { gravity = Gravity.CENTER }
        stateView = tv("", 15f).apply { gravity = Gravity.CENTER }
        warnView = tv("", 14f, false, 0xFFC62828.toInt())
        sizeLabel = tv("", 16f, true)

        val startSize = p.getInt("size_dp", 96)
        sizeLabel.text = "캐릭터 크기: ${startSize}dp"
        val seek = SeekBar(this).apply {
            max = 152                       // 48dp ~ 200dp
            progress = startSize - 48
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    val size = v + 48
                    sizeLabel.text = "캐릭터 크기: ${size}dp"
                    if (fromUser) p.edit().putInt("size_dp", size).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }

        root.addView(titleView)
        root.addView(stateView)
        root.addView(sizeLabel)
        root.addView(seek)
        root.addView(btn("캐릭터 실행") { startPet() })
        root.addView(btn("캐릭터 종료") {
            stopService(Intent(this, OverlayService::class.java))
            window.decorView.postDelayed({ refresh() }, 400)
        })
        root.addView(warnView)
        root.addView(btn("상세 설정") { startActivity(Intent(this, SettingsActivity::class.java)) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (intent?.getBooleanExtra("exited", false) == true) {
            intent.removeExtra("exited")
            AlertDialog.Builder(this)
                .setTitle("캐릭터를 종료했어요")
                .setMessage("캐릭터를 다시 띄울까요?")
                .setPositiveButton("다시 실행") { _, _ -> startPet() }
                .setNegativeButton("그대로 두기", null)
                .show()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun refresh() {
        val name = Cfg.prefs(this).getString("char_name", "도우미").orEmpty().ifBlank { "도우미" }
        titleView.text = "🐱 $name"
        stateView.text = if (OverlayService.running) "● 실행 중" else "○ 꺼져 있음"
        val missing = ArrayList<String>()
        if (!Perms.overlay(this)) missing.add("다른 앱 위에 표시(필수)")
        missing.addAll(Perms.missingOptional(this))
        warnView.text = if (missing.isEmpty()) "" else
            "⚠ 꺼져 있는 권한: ${missing.joinToString(", ")}\n아래 '상세 설정'에서 허용 방법을 확인해 주세요."
    }

    private fun startPet() {
        if (!Perms.overlay(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 먼저 허용해 주세요", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !Perms.notif(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        window.decorView.postDelayed({ refresh() }, 600)
    }
}