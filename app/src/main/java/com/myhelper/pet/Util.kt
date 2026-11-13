package com.myhelper.pet

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.tv(t: String, sp: Float = 15f, bold: Boolean = false, color: Int = 0xFF222222.toInt()): TextView =
    TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

fun Context.btn(t: String, onClick: () -> Unit): Button =
    Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

object Cfg {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    val H_INTERVALS = longArrayOf(10_000, 20_000, 60_000, 180_000)
    val H_LABELS = arrayOf("10초", "20초", "1분", "3분")

    // 0 없음 1 통통 2 좌우 3 맥박 4 회전 5 깜빡 6 떨어지기
    val EFFECT_NAMES = arrayOf("없음", "통통 튀기", "좌우 흔들기", "커졌다 작아지기", "회전 흔들기", "깜빡이기", "위에서 떨어지기")
    val EVENT_KEYS = arrayOf("b", "c", "d", "e", "f", "g")
    val EVENT_LABELS = arrayOf(
        "b. 터치했을 때", "c. 더블 터치했을 때", "d. 꾹 눌러 들고 있을 때",
        "e. 배터리 부족", "f. 같은 앱 오래 사용", "g. 알림이 왔을 때"
    )
    private val EVENT_DEFAULT = intArrayOf(1, 3, 2, 2, 1, 5)

    fun effect(p: SharedPreferences, key: String): Int {
        val idx = EVENT_KEYS.indexOf(key)
        return p.getInt("eff_$key", EVENT_DEFAULT[idx])
    }

    // 이미지 슬롯 1~5 = a, b, d, e, i
    val SLOT_TITLES = arrayOf(
        "", "a. 기본 상태", "b. 터치했을 때", "d. 꾹 눌러 들고 있을 때", "e. 배터리 부족", "i. 대기 상태", "g. 알림이 왔을 때", "이동. 돌아다닐 때"
    )
    val SLOT_DESCS = arrayOf(
        "",
        "평소에 계속 반복되는 기본 모습이에요.",
        "캐릭터를 한 번 터치했을 때 나오는 모습이에요.",
        "1초 동안 꾹 눌러서 들고 있는 동안 나오는 모습이에요.",
        "배터리가 설정한 %까지 떨어졌을 때 나오는 모습이에요.",
        "5분 동안 아무 일도 없을 때 계속 반복되는 모습이에요. (혼잣말을 끄면 30초)",
        "다른 앱에서 알림이 왔을 때 나오는 모습이에요. 설정하지 않으면 기본(a) 이미지를 그대로 써요.",
        "화면을 돌아다닐 때 나오는 모습이에요. 오른쪽을 바라보는 모습으로 만들어 주세요. 왼쪽으로 갈 때 자동으로 좌우 반전돼요. 설정하지 않으면 기본(a) 이미지를 써요."
    )
}

object Perms {
    fun overlay(c: Context) = Settings.canDrawOverlays(c)

    fun listener(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(c.packageName)
    }

    @Suppress("DEPRECATION")
    fun usage(c: Context): Boolean {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val m = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return m == AppOpsManager.MODE_ALLOWED
    }

    fun notif(c: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun battery(c: Context): Boolean =
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(c.packageName)

    fun missingOptional(c: Context): List<String> {
        val l = ArrayList<String>()
        if (!notif(c)) l.add("알림 표시")
        if (!listener(c)) l.add("알림 접근")
        if (!usage(c)) l.add("사용 정보 접근")
        if (!battery(c)) l.add("배터리 최적화 제외")
        return l
    }
}

data class AppInfo(val pkg: String, val label: String)

object Apps {
    fun launchable(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppInfo(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != c.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label }
    }

    fun label(c: Context, pkg: String?): String {
        if (pkg.isNullOrEmpty()) return "선택 안 됨"
        return try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    fun icon(c: Context, pkg: String?): Drawable? {
        if (pkg.isNullOrEmpty()) return null
        return try { c.packageManager.getApplicationIcon(pkg) } catch (e: Exception) { null }
    }

    fun launch(c: Context, pkg: String): Boolean {
        return try {
            val i = c.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun homePackages(c: Context): Set<String> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return c.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }
}

object Media {
    private val EMOJI = arrayOf("", "😺", "😸", "😲", "😿", "😴", "😮", "🐾")

    fun defaultDrawable(c: Context, slot: Int, px: Int = 256): Drawable {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * 0.8f
            textAlign = Paint.Align.CENTER
        }
        val y = px / 2f - (paint.descent() + paint.ascent()) / 2f
        cv.drawText(EMOJI[slot.coerceIn(1, 7)], px / 2f, y, paint)
        return BitmapDrawable(c.resources, bmp)
    }

    /** 움직이는 이미지의 1회 재생 길이(ms). 정지 이미지/알 수 없으면 0 */
    fun animDuration(b: ByteArray): Int {
        if (b.size > 13 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()) {
            return try { gif(b) } catch (e: Exception) { 0 }
        }
        if (b.size > 16 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(b, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return try { webp(b) } catch (e: Exception) { 0 }
        }
        return 0
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF

    private fun gif(b: ByteArray): Int {
        var p = 13
        val packed = u8(b, 10)
        if ((packed and 0x80) != 0) p += 3 * (1 shl ((packed and 7) + 1))
        var cs = 0
        loop@ while (p < b.size) {
            when (u8(b, p)) {
                0x21 -> {
                    if (p + 5 < b.size && u8(b, p + 1) == 0xF9) {
                        val d = u8(b, p + 4) or (u8(b, p + 5) shl 8)
                        cs += if (d <= 1) 10 else d
                    }
                    p += 2
                    while (p < b.size && u8(b, p) != 0) p += u8(b, p) + 1
                    p++
                }
                0x2C -> {
                    if (p + 10 > b.size) break@loop
                    val pk = u8(b, p + 9)
                    p += 10
                    if ((pk and 0x80) != 0) p += 3 * (1 shl ((pk and 7) + 1))
                    p++
                    while (p < b.size && u8(b, p) != 0) p += u8(b, p) + 1
                    p++
                }
                else -> break@loop
            }
        }
        return cs * 10
    }

    private fun webp(b: ByteArray): Int {
        var p = 12
        var total = 0
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val len = u8(b, p + 4) or (u8(b, p + 5) shl 8) or (u8(b, p + 6) shl 16) or (u8(b, p + 7) shl 24)
            if (id == "ANMF" && p + 8 + 15 <= b.size) {
                total += u8(b, p + 20) or (u8(b, p + 21) shl 8) or (u8(b, p + 22) shl 16)
            }
            if (len < 0) break
            p += 8 + len + (len and 1)
        }
        return total
    }
}