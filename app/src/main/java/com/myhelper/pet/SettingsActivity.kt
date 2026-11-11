package com.myhelper.pet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import java.io.File

class SettingsActivity : Activity() {

    private lateinit var p: SharedPreferences
    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Cfg.prefs(this)
        scroll = ScrollView(this)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // ---------------- 공통 도우미 ----------------
    private fun header(t: String) {
        root.addView(tv(t, 19f, true, 0xFF1565C0.toInt()).apply { setPadding(0, dp(28), 0, dp(6)) })
    }

    private fun note(t: String) = root.addView(tv(t, 13f, false, 0xFF666666.toInt()))

    private fun edit(key: String, def: String, hintText: String): EditText =
        EditText(this).apply {
            setText(p.getString(key, def))
            hint = hintText
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    p.edit().putString(key, s?.toString()?.trim().orEmpty()).apply()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

    private fun spinner(items: Array<String>, current: Int, onPick: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, items)
            setSelection(current.coerceIn(0, items.size - 1))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

    private fun build() {
        val keepY = if (::root.isInitialized) scroll.scrollY else 0
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(44), dp(20), dp(48))
        }
        scroll.removeAllViews()
        scroll.addView(root)
        root.addView(tv("상세 설정", 26f, true))

        basicSection()
        permSection()
        menuSection()
        batterySection()
        idleSection()
        notifSection()
        lookSection()
        effectSection()
        imageSection()

        scroll.post { scroll.scrollTo(0, keepY) }
    }

    // ---------------- 기본 ----------------
    private fun basicSection() {
        header("기본 정보")
        root.addView(tv("사용자명 (말풍선에서 나를 부르는 이름)", 14f))
        root.addView(edit("user_name", "사용자", "예: 민수"))
        root.addView(tv("캐릭터명", 14f))
        root.addView(edit("char_name", "도우미", "예: 도우미"))
        root.addView(tv("말투", 14f))
        val group = RadioGroup(this)
        val a = RadioButton(this).apply { text = "존칭 사용 (~입니다, ~님)"; id = 1001 }
        val b = RadioButton(this).apply { text = "존칭 사용 안 함 (~야, ~해)"; id = 1002 }
        group.addView(a)
        group.addView(b)
        group.check(if (p.getBoolean("honorific", true)) 1001 else 1002)
        group.setOnCheckedChangeListener { _, id -> p.edit().putBoolean("honorific", id == 1001).apply() }
        root.addView(group)
    }

    // ---------------- 권한 ----------------
    private fun permRow(name: String, ok: Boolean, desc: String, how: String, btnText: String, action: () -> Unit) {
        root.addView(tv((if (ok) "✅ " else "❌ ") + name, 16f, true).apply { setPadding(0, dp(14), 0, 0) })
        root.addView(tv(desc, 13f))
        root.addView(tv(how, 13f, false, 0xFF666666.toInt()))
        root.addView(btn(btnText) { action() })
    }

    private fun permSection() {
        header("필요한 권한")
        note("꺼져 있는 권한은 해당 기능만 동작하지 않아요. 허용하고 돌아오면 표시가 바로 바뀌어요.")

        permRow(
            "다른 앱 위에 표시 (필수)", Perms.overlay(this),
            "캐릭터를 바탕화면과 다른 앱 위에 띄우는 데 꼭 필요해요.",
            "설정 화면에서 '나의 도우미'를 찾아 스위치를 켜 주세요.", "설정 열기"
        ) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        permRow(
            "알림 표시", Perms.notif(this),
            "캐릭터가 실행 중일 때 알림창에 표시되는 알림이에요. (안드로이드 13 이상)",
            "나오는 팝업에서 '허용'을 눌러 주세요.", "권한 요청"
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        permRow(
            "알림 접근", Perms.listener(this),
            "다른 앱에서 알림이 왔을 때 캐릭터가 알려주는 기능(g)에 쓰여요. 음악·통화 알림은 제외돼요.",
            "설정 화면에서 '나의 도우미'를 켜 주세요.\n" +
                "※ APK를 직접 설치했다면 스위치가 회색으로 막혀 있을 수 있어요. 그땐 아래 '앱 정보 열기' → 오른쪽 위 ⋮ → '제한된 설정 허용'을 누른 뒤 다시 켜 주세요.",
            "알림 접근 설정 열기"
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }

        permRow(
            "사용 정보 접근", Perms.usage(this),
            "같은 앱을 30분 넘게 쓰는지 확인하는 기능(f)에 쓰여요.",
            "설정 화면에서 '나의 도우미'를 찾아 켜 주세요. (마찬가지로 막혀 있으면 '제한된 설정 허용' 먼저)",
            "사용 정보 설정 열기"
        ) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }

        permRow(
            "배터리 최적화 제외", Perms.battery(this),
            "절전 기능 때문에 캐릭터가 저절로 꺼지는 걸 막아줘요.",
            "나오는 팝업에서 '허용'을 눌러 주세요. 삼성·샤오미 등은 기기 설정의 '절전 예외'도 확인해 주세요.",
            "허용 요청"
        ) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        root.addView(btn("앱 정보 열기 (제한된 설정 허용용)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
    }

    // ---------------- c 메뉴 ----------------
    private fun appPickButton(key: String, title: String) {
        val pkg = p.getString(key, null)
        root.addView(btn("$title: ${Apps.label(this, pkg)}") {
            val apps = Apps.launchable(this)
            val names = (listOf("선택 해제") + apps.map { it.label }).toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(names) { _, which ->
                    if (which == 0) p.edit().remove(key).apply()
                    else p.edit().putString(key, apps[which - 1].pkg).apply()
                    build()
                }
                .show()
        })
    }

    private fun menuSection() {
        header("더블 터치 메뉴 (c)")
        val group = RadioGroup(this)
        group.addView(RadioButton(this).apply { text = "날씨 + 앱 바로가기 1개 + O/X 룰렛"; id = 2001 })
        group.addView(RadioButton(this).apply { text = "앱 바로가기 3개"; id = 2002 })
        group.check(if (p.getInt("menu_mode", 1) == 1) 2001 else 2002)
        group.setOnCheckedChangeListener { _, id -> p.edit().putInt("menu_mode", if (id == 2001) 1 else 2).apply() }
        root.addView(group)

        root.addView(tv("날씨를 볼 도시 (인터넷 필요)", 14f).apply { setPadding(0, dp(12), 0, 0) })
        root.addView(edit("city", "서울", "예: 서울, 부산, Tokyo"))

        note("바로가기 앱 (첫 번째 메뉴는 앱 1만 사용, 두 번째 메뉴는 앱 1~3 모두 사용)")
        appPickButton("app1", "앱 1")
        appPickButton("app2", "앱 2")
        appPickButton("app3", "앱 3")
    }

    // ---------------- e 배터리 ----------------
    private fun batterySection() {
        header("배터리 경고 (e)")
        val label = tv("", 15f)
        val cur = p.getInt("battery_pct", 20)
        label.text = "배터리가 ${cur}% 이하일 때 알려주기"
        root.addView(label)
        root.addView(SeekBar(this).apply {
            max = 45                       // 5% ~ 50%
            progress = cur - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    label.text = "배터리가 ${v + 5}% 이하일 때 알려주기"
                    if (fromUser) p.edit().putInt("battery_pct", v + 5).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        note("한 번 알린 뒤에는 충전을 시작하기 전까지 다시 알리지 않아요.")
    }

    // ---------------- h ----------------
    private fun idleSection() {
        header("혼잣말 (h)")
        val on = p.getBoolean("h_on", true)
        root.addView(CheckBox(this).apply {
            text = "혼잣말 사용"
            isChecked = on
            setOnCheckedChangeListener { _, c -> p.edit().putBoolean("h_on", c).apply(); build() }
        })
        if (on) {
            note("아무 일도 없을 때 이 간격마다 말을 걸어요. 5분 이상 조용하면 대기 상태(i)가 되고 혼잣말은 멈춰요.")
            root.addView(spinner(Cfg.H_LABELS, p.getInt("h_idx", 2)) { p.edit().putInt("h_idx", it).apply() })
        } else {
            note("혼잣말을 끄면 5분 대신 30초 동안 아무 일도 없을 때 대기 상태(i)가 돼요.")
        }
    }

    // ---------------- g ----------------
    private fun notifSection() {
        header("알림 말풍선 (g)")
        val muted = p.getStringSet("muted", emptySet())?.size ?: 0
        root.addView(btn("알림을 받지 않을 앱 선택 (현재 ${muted}개)") {
            val apps = Apps.launchable(this)
            val set = (p.getStringSet("muted", emptySet()) ?: emptySet()).toMutableSet()
            val checked = BooleanArray(apps.size) { apps[it].pkg in set }
            AlertDialog.Builder(this)
                .setTitle("알림을 받지 않을 앱")
                .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, c ->
                    if (c) set.add(apps[i].pkg) else set.remove(apps[i].pkg)
                }
                .setPositiveButton("확인") { _, _ -> p.edit().putStringSet("muted", set).apply(); build() }
                .setNegativeButton("취소", null)
                .show()
        })
    }

    // ---------------- 모양 ----------------
    private fun slider(prefix: String, unit: String, key: String, lo: Int, hi: Int, def: Int) {
        val cur = p.getInt(key, def).coerceIn(lo, hi)
        val label = tv("$prefix: $cur$unit", 14f).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(label)
        root.addView(SeekBar(this).apply {
            this.max = hi - lo
            progress = cur - lo
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    label.text = "$prefix: ${v + lo}$unit"
                    if (fromUser) p.edit().putInt(key, v + lo).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
    }

    private fun lookSection() {
        header("말풍선과 바 모양")
        slider("말풍선 글자 크기", "sp", "bubble_sp", 10, 24, 14)
        slider("말풍선 투명도", "%", "bubble_alpha", 30, 100, 95)
        slider("아래 바 높이", "dp", "bar_dp", 40, 200, 90)
        note("바가 클수록 놓으면 종료되는 영역이 넓어져 실수로 종료하기 쉬워요. 말풍선 투명도는 배경에만 적용되고 글자는 선명하게 유지돼요.")
    }

    // ---------------- 효과 ----------------
    private fun effectSection() {
        header("캐릭터 움직임 효과")
        root.addView(tv("움직임 방식", 14f))
        val g = RadioGroup(this)
        g.addView(RadioButton(this).apply { text = "말풍선이 끝날 때까지 계속 반복"; id = 3001 })
        g.addView(RadioButton(this).apply { text = "한 번만 실행"; id = 3002 })
        g.check(if (p.getBoolean("fx_repeat", true)) 3001 else 3002)
        g.setOnCheckedChangeListener { _, id -> p.edit().putBoolean("fx_repeat", id == 3001).apply() }
        root.addView(g)
        root.addView(tv("속도", 14f).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(spinner(arrayOf("느리게", "보통", "빠르게"), p.getInt("fx_speed", 1)) {
            p.edit().putInt("fx_speed", it).apply()
        })
        note("더블 터치(c)의 효과는 항상 한 번만 실행돼요. '한 번만'을 고르면 꾹 누르기(d)의 떨림도 한 번 떨고 멈춰요. 아래에서 상황별 효과 종류를 골라요.")
        for (i in Cfg.EVENT_KEYS.indices) {
            val key = Cfg.EVENT_KEYS[i]
            root.addView(tv(Cfg.EVENT_LABELS[i], 14f).apply { setPadding(0, dp(10), 0, 0) })
            root.addView(spinner(Cfg.EFFECT_NAMES, Cfg.effect(p, key)) { p.edit().putInt("eff_$key", it).apply() })
        }
    }

    // ---------------- 이미지 ----------------
    private fun slotPreview(slot: Int): Drawable {
        val f = File(filesDir, "img_$slot")
        if (f.exists()) {
            try {
                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    val m = maxOf(info.size.width, info.size.height)
                    if (m > 256) dec.setTargetSampleSize((m + 255) / 256)
                }
                if (d is AnimatedImageDrawable) {
                    d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    d.start()
                }
                return d
            } catch (e: Exception) { }
        }
        return Media.defaultDrawable(this, slot)
    }

    private fun imageSection() {
        header("캐릭터 이미지")
        note("GIF, PNG, JPG, WebP 모두 쓸 수 있어요. 정사각형 이미지를 권장하며, 이 정사각형이 캐릭터의 크기이자 터치 영역이에요. 정사각형이 아니면 틀 안에 맞춰서 보여줘요. 설정하지 않으면 기본 이모지가 나와요.")
        for (slot in 1..5) {
            root.addView(tv(Cfg.SLOT_TITLES[slot], 16f, true).apply { setPadding(0, dp(16), 0, 0) })
            root.addView(tv(Cfg.SLOT_DESCS[slot], 13f, false, 0xFF666666.toInt()))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(slotPreview(slot))
            }, LinearLayout.LayoutParams(dp(72), dp(72)))
            row.addView(btn("이미지 선택") {
                startActivityForResult(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "image/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }, 100 + slot
                )
            })
            row.addView(btn("기본으로") {
                File(filesDir, "img_$slot").delete()
                p.edit().putLong("img_ver", System.currentTimeMillis()).apply()
                build()
            })
            root.addView(row)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val slot = requestCode - 100
        if (resultCode != RESULT_OK || slot !in 1..5) return
        val uri = data?.data ?: return
        try {
            val tmp = File(filesDir, "img_${slot}.tmp")
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("열 수 없는 파일")
            // 읽을 수 있는 이미지인지 확인
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(tmp)) { dec, info, _ ->
                val m = maxOf(info.size.width, info.size.height)
                if (m > 128) dec.setTargetSampleSize((m + 127) / 128)
            }
            val dst = File(filesDir, "img_$slot")
            dst.delete()
            tmp.renameTo(dst)
            p.edit().putLong("img_ver", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            File(filesDir, "img_${slot}.tmp").delete()
            Toast.makeText(this, "이 이미지는 사용할 수 없어요", Toast.LENGTH_LONG).show()
        }
    }
}