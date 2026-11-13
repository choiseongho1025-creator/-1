package com.myhelper.pet

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.myhelper.pet.STOP"
        const val CHANNEL = "pet_service"
        @Volatile var running = false
        @Volatile var instance: OverlayService? = null
    }

    // ---------- 기본 ----------
    private val mh = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var vibrator: Vibrator? = null
    private var usm: UsageStatsManager? = null
    private var inited = false

    private val T_EVENT = Any()
    private val T_IDLE = Any()
    private val T_H = Any()
    private val T_TAP = Any()
    private val T_LONG = Any()
    private val T_USAGE = Any()

    private var screenW = 1080
    private var screenH = 1920

    // ---------- 뷰 ----------
    private lateinit var charView: ImageView
    private lateinit var charLp: WindowManager.LayoutParams
    private var charAdded = false
    private var posX = 0
    private var posY = 0
    private var offX = 0
    private var offY = 0

    private var bubbleView: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var bubbleAdded = false

    private var menuView: LinearLayout? = null
    private var menuLp: WindowManager.LayoutParams? = null
    private var menuAdded = false

    private var barView: View? = null
    private var barAdded = false

    // ---------- 상태 ----------
    private var busy = false
    private var idleMode = false
    private var dragging = false
    private var overBar = false
    private var eventSerial = 0
    private var activeNotifPkg: String? = null
    private var activeNotifIntent: PendingIntent? = null
    private var effectAnim: ValueAnimator? = null
    private var walkAnim: ValueAnimator? = null
    private var walking = false
    private var parked = false
    private val T_WALK = Any()
    private var sideL: View? = null
    private var sideR: View? = null
    private var overSide = 0

    private var currentSlot = -1
    private var lastSlot = 1
    private val drawableCache = HashMap<Int, Drawable>()
    private val loopCache = HashMap<Int, Int>()

    // 터치
    private var downX = 0f
    private var downY = 0f
    private var grabDX = 0f
    private var grabDY = 0f
    private var moved = false
    private var longPressed = false
    private var tapPending = false

    // 배터리 / 사용량
    private var batteryArmed = true
    private var fgPkg: String? = null
    private var fgSince = 0L
    private var nextWarnAt = 0L
    private var lastQuery = 0L
    private var screenWasOff = false
    private var screenOnAt = 0L
    private var homePkgs: Set<String> = emptySet()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "size_dp" -> relayout()
            "img_ver" -> {
                (charView.drawable as? AnimatedImageDrawable)?.stop()
                drawableCache.clear()
                loopCache.clear()
                currentSlot = -1
                setImage(lastSlot)
            }
            "h_idx" -> if (!busy && !idleMode) scheduleH()
            "h_on" -> { resetIdle(); if (!busy && !dragging) toA() }
            "walk_on" -> { if (walkOn()) scheduleWalk() else { setParked(false); stopWalk() } }
            "walk_mode", "walk_wait" -> if (!busy && !dragging) scheduleWalk()
        }
    }

    // ============ 생명주기 ============
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!inited) {
            startAsForeground()
            if (!Settings_canDraw()) {
                Toast.makeText(this, "'다른 앱 위에 표시' 권한이 필요해요", Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            initAll()
        }
        return START_STICKY
    }

    private fun Settings_canDraw() = android.provider.Settings.canDrawOverlays(this)

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "도우미 실행 상태", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val charName = Cfg.prefs(this).getString("char_name", "도우미").orEmpty().ifBlank { "도우미" }
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("$charName 실행 중")
            .setContentText("캐릭터가 화면에 떠 있어요")
            .setSmallIcon(android.R.drawable.star_on)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "종료", stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
    }

    private fun initAll() {
        inited = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = Cfg.prefs(this)
        vibrator = getSystemService(Vibrator::class.java)
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        homePkgs = Apps.homePackages(this)
        loadScreen()
        buildChar()
        parked = prefs.getBoolean("parked", false) && walkOn()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        running = true
        instance = this
        toA()
        resetIdle()
        mh.postAtTime({ pollUsage(); scheduleUsage() }, T_USAGE, SystemClock.uptimeMillis() + 5000)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!inited) return
        loadScreen()
        relayout()
    }

    override fun onDestroy() {
        running = false
        instance = null
        if (inited) {
            mh.removeCallbacksAndMessages(null)
            try { unregisterReceiver(batteryReceiver) } catch (e: Exception) { }
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
            effectAnim?.cancel()
            walkAnim?.cancel()
            savePos()
            hideBubble(); closeMenu(); hideBar(); hideSideBars()
            if (charAdded) { try { wm.removeView(charView) } catch (e: Exception) { }; charAdded = false }
        }
        super.onDestroy()
    }

    // ============ 화면 / 캐릭터 창 ============
    @Suppress("DEPRECATION")
    private fun loadScreen() {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        screenW = dm.widthPixels
        screenH = dm.heightPixels
    }

    private fun sizePx() = dp(prefs.getInt("size_dp", 96))

    private fun overlayLp(w: Int, h: Int, extraFlags: Int = 0): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun buildChar() {
        charView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnTouchListener { _, e -> onCharTouch(e); true }
        }
        val s = sizePx()
        charLp = overlayLp(s, s, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        posX = prefs.getInt("pos_x", screenW - s - dp(12))
        posY = prefs.getInt("pos_y", screenH / 2)
        clampPos()
        charLp.x = posX
        charLp.y = posY
        wm.addView(charView, charLp)
        charAdded = true
    }

    private fun relayout() {
        if (!charAdded) return
        val s = sizePx()
        charLp.width = s
        charLp.height = s
        clampPos()
        applyPos()
        if (bubbleAdded) refreshBubble()
    }

    private fun clampPos() {
        val s = charLp.width
        posX = posX.coerceIn(0, max(0, screenW - s))
        posY = posY.coerceIn(0, max(0, screenH - s))
    }

    private fun applyPos() {
        if (!charAdded) return
        charLp.x = posX + offX
        charLp.y = posY + offY
        try { wm.updateViewLayout(charView, charLp) } catch (e: Exception) { }
    }

    private fun savePos() {
        if (!inited) return
        prefs.edit().putInt("pos_x", posX).putInt("pos_y", posY).apply()
    }

    // ============ 이미지 ============
    private fun loadSlot(slot: Int): Drawable {
        drawableCache[slot]?.let { return it }
        val f = File(filesDir, "img_$slot")
        var d: Drawable? = null
        var loop = 0
        if (f.exists()) {
            try {
                d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                    val m = max(info.size.width, info.size.height)
                    if (m > 512) dec.setTargetSampleSize((m + 511) / 512)
                }
                if (d is AnimatedImageDrawable) loop = Media.animDuration(f.readBytes())
            } catch (e: Exception) {
                d = null
            }
        }
        val result = d ?: Media.defaultDrawable(this, slot)
        drawableCache[slot] = result
        loopCache[slot] = loop
        return result
    }

    private fun loopMs(slot: Int): Int {
        loadSlot(slot)
        return loopCache[slot] ?: 0
    }

    private fun setImage(slot: Int) {
        lastSlot = slot
        val d = loadSlot(slot)
        if (currentSlot == slot && charView.drawable === d) {
            (d as? AnimatedImageDrawable)?.let { if (!it.isRunning) it.start() }
            return
        }
        (charView.drawable as? AnimatedImageDrawable)?.stop()
        charView.setImageDrawable(d)
        currentSlot = slot
        if (d is AnimatedImageDrawable) {
            d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            d.start()
        }
    }

    // ============ 말풍선 ============
    private fun roundBg(fill: Int, stroke: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun placeAbove(v: View, lp: WindowManager.LayoutParams) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec((screenW * 0.9f).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val w = v.measuredWidth
        val h = v.measuredHeight
        val s = charLp.width
        lp.x = (posX + s / 2 - w / 2).coerceIn(0, max(0, screenW - w))
        var y = posY - h - dp(6)
        if (y < 0) y = posY + s + dp(6)
        lp.y = y
    }

    private fun showBubble(text: CharSequence) {
        val v = bubbleView ?: TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF222222.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            maxWidth = (screenW * 0.7f).toInt()
            background = roundBg(0xF2FFFFFF.toInt(), 0xFF555555.toInt(), 14)
        }.also { bubbleView = it }
        val lp = bubbleLp ?: overlayLp(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        ).also { bubbleLp = it }
        v.textSize = prefs.getInt("bubble_sp", 14).toFloat()
        val alpha = (prefs.getInt("bubble_alpha", 95) * 255 / 100).coerceIn(0, 255)
        v.background = roundBg((alpha shl 24) or 0xFFFFFF, (alpha shl 24) or 0x555555, 14)
        v.text = text
        placeAbove(v, lp)
        if (!bubbleAdded) {
            wm.addView(v, lp)
            bubbleAdded = true
        } else {
            try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
        }
    }

    private fun refreshBubble() {
        val v = bubbleView ?: return
        val lp = bubbleLp ?: return
        placeAbove(v, lp)
        try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
    }

    private fun hideBubble() {
        if (bubbleAdded) {
            try { wm.removeView(bubbleView) } catch (e: Exception) { }
            bubbleAdded = false
        }
    }

    // ============ 효과 ============
    private fun stopEffect() {
        effectAnim?.cancel()
        effectAnim = null
        offX = 0
        offY = 0
        charView.scaleX = 1f
        charView.scaleY = 1f
        charView.rotation = 0f
        charView.alpha = 1f
        applyPos()
    }

    private fun startEffect(effect: Int, once: Boolean) {
        stopEffect()
        if (effect <= 0) return
        val s = charLp.width.toFloat()
        val k = when (prefs.getInt("fx_speed", 1)) { 0 -> 1.6f; 2 -> 0.6f; else -> 1f }
        val single = once || !prefs.getBoolean("fx_repeat", true)
        fun ms(base: Long): Long = (base * k).toLong()
        val a: ValueAnimator = when (effect) {
            1 -> ValueAnimator.ofFloat(0f, -s * 0.3f).apply {
                duration = ms(350)
                interpolator = DecelerateInterpolator()
                addUpdateListener { offY = (it.animatedValue as Float).toInt(); applyPos() }
            }
            2 -> ValueAnimator.ofFloat(-s * 0.1f, s * 0.1f).apply {
                duration = ms(100)
                addUpdateListener { offX = (it.animatedValue as Float).toInt(); applyPos() }
            }
            3 -> ValueAnimator.ofFloat(1f, 0.8f).apply {
                duration = ms(350)
                addUpdateListener {
                    val v = it.animatedValue as Float
                    charView.scaleX = v
                    charView.scaleY = v
                }
            }
            4 -> ValueAnimator.ofFloat(-10f, 10f).apply {
                duration = ms(180)
                charView.scaleX = 0.85f
                charView.scaleY = 0.85f
                addUpdateListener { charView.rotation = it.animatedValue as Float }
            }
            5 -> ValueAnimator.ofFloat(1f, 0.25f).apply {
                duration = ms(300)
                addUpdateListener { charView.alpha = it.animatedValue as Float }
            }
            else -> ValueAnimator.ofFloat(-s * 1.2f, 0f).apply {
                duration = ms(650)
                interpolator = BounceInterpolator()
                addUpdateListener { offY = (it.animatedValue as Float).toInt(); applyPos() }
            }
        }
        if (effect != 6) {
            a.repeatMode = ValueAnimator.REVERSE
            a.repeatCount = if (single) 1 else ValueAnimator.INFINITE
        }
        a.start()
        effectAnim = a
    }

    // ============ 이벤트 공통 ============
    private fun postEv(delayMs: Long, block: () -> Unit) {
        mh.postAtTime({ block() }, T_EVENT, SystemClock.uptimeMillis() + delayMs)
    }

    private fun cancelEvent() {
        stopWalk()
        mh.removeCallbacksAndMessages(T_EVENT)
        eventSerial++
        stopEffect()
        hideBubble()
        closeMenu()
        activeNotifPkg = null; activeNotifIntent = null
    }

    /** 이전 이미지/말풍선을 즉시 새것으로 교체. 이미지가 말풍선보다 길면 시간을 늘림 */
    private fun showEvent(
        slot: Int, text: CharSequence?, baseMs: Long, effect: Int,
        once: Boolean = false, countIdle: Boolean = true
    ) {
        cancelEvent()
        busy = true
        mh.removeCallbacksAndMessages(T_H)
        if (countIdle) resetIdle()
        setImage(slot)
        val dur = max(baseMs, loopMs(slot).toLong())
        if (text != null) showBubble(text) else hideBubble()
        startEffect(effect, once)
        postEv(dur) { toA() }
    }

    /** 기본(a) 상태로 복귀. 5분 동안 아무 일 없으면 i 이미지 */
    private fun toA() {
        stopWalk()
        mh.removeCallbacksAndMessages(T_EVENT)
        stopEffect()
        hideBubble()
        closeMenu()
        activeNotifPkg = null; activeNotifIntent = null
        busy = false
        if (dragging) {
            setImage(3)
            startEffect(Cfg.effect(prefs, "d"), false)
            if (overBar) showBubble(dText())
        } else {
            setImage(if (idleMode) 5 else 1)
        }
        scheduleH()
        scheduleWalk()
    }

    private fun resetIdle() {
        idleMode = false
        mh.removeCallbacksAndMessages(T_IDLE)
        mh.postAtTime({ enterIdle() }, T_IDLE, SystemClock.uptimeMillis() + idleDelay())
    }

    private fun idleDelay(): Long = if (prefs.getBoolean("h_on", true)) 5 * 60_000L else 30_000L

    private fun enterIdle() {
        idleMode = true
        stopWalk()
        if (!busy && !dragging) setImage(5)
    }

    // ============ 문구 ============
    private fun user(): String = prefs.getString("user_name", "사용자").orEmpty().ifBlank { "사용자" }
    private fun pick(a: String, b: String): String = if (prefs.getBoolean("honorific", true)) a else b

    private fun dText() = pick("${user()}님이 원하신다면..", "${user()}..진심이야?")

    private val hA = arrayOf(
        "{u}님, 많이 바쁘신가요?", "쉬엄쉬엄 하세요~", "혹시 요즘 고민이 있다면 저에게 말씀해주세요.",
        "오늘도 힘내세요 {u}님.", "제가 곁에 있을게요 {u}님."
    )
    private val hB = arrayOf(
        "{u}, 나 심심해", "천천히 해~", "요즘 고민되는거 있어?", "오늘도 힘내!", "내가 옆에 있어줄게"
    )

    // ============ h: 아무것도 하지 않을 때 ============
    private fun scheduleH() {
        mh.removeCallbacksAndMessages(T_H)
        if (!prefs.getBoolean("h_on", true)) return
        val iv = Cfg.H_INTERVALS[prefs.getInt("h_idx", 2).coerceIn(0, 3)]
        mh.postAtTime({ onHTick() }, T_H, SystemClock.uptimeMillis() + iv)
    }

    private fun onHTick() {
        if (idleMode) return              // i 상태에서는 h 정지
        if (busy || dragging) { scheduleH(); return }
        val i = Random.nextInt(5)
        val msg = pick(hA[i], hB[i]).replace("{u}", user())
        showEvent(1, msg, 3000, 0, once = false, countIdle = false)
    }

    // ============ 이동 (돌아다니기) ============
    private fun walkOn() = prefs.getBoolean("walk_on", false)

    private fun walkSlot(): Int = if (File(filesDir, "img_7").exists()) 7 else 1

    private fun setParked(v: Boolean) {
        parked = v
        prefs.edit().putBoolean("parked", v).apply()
    }

    private fun scheduleWalk() {
        mh.removeCallbacksAndMessages(T_WALK)
        if (!walkOn() || parked || idleMode) return
        val wait: Long = if (prefs.getInt("walk_mode", 0) == 1) {
            5000L + Random.nextInt(10001)
        } else {
            prefs.getInt("walk_wait", 10).coerceIn(5, 15) * 1000L
        }
        mh.postAtTime({ startWalk() }, T_WALK, SystemClock.uptimeMillis() + wait)
    }

    private fun startWalk() {
        if (!walkOn() || parked || busy || dragging || idleMode || walking || !charAdded) return
        val s = charLp.width
        val bottomY = max(0, screenH - s - barHeight() - dp(8))
        val tx = Random.nextInt(max(1, screenW - s + 1))
        val ty = when (prefs.getInt("walk_range", 0)) {
            1 -> bottomY
            2 -> posY
            else -> Random.nextInt(max(1, bottomY + 1))
        }
        val dx = tx - posX
        val dy = ty - posY
        val dist = hypot(dx.toFloat(), dy.toFloat())
        if (dist < dp(20)) { scheduleWalk(); return }
        val speedDp = when (prefs.getInt("walk_speed", 1)) { 0 -> 35f; 2 -> 120f; else -> 70f }
        val duration = (dist / (speedDp * resources.displayMetrics.density) * 1000f).toLong().coerceAtLeast(300L)
        if (Math.abs(dx) > dp(4)) {
            val faceRight = prefs.getInt("walk_face", 0) == 0
            val goingLeft = dx < 0
            charView.scaleX = if (faceRight == goingLeft) -1f else 1f
        }
        walking = true
        setImage(walkSlot())
        val sx = posX
        val sy = posY
        var canceled = false
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = duration
        a.interpolator = LinearInterpolator()
        a.addUpdateListener {
            val f = it.animatedValue as Float
            posX = (sx + dx * f).toInt()
            posY = (sy + dy * f).toInt()
            applyPos()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) { canceled = true }
            override fun onAnimationEnd(animation: Animator) {
                if (canceled) return
                walking = false
                walkAnim = null
                charView.scaleX = 1f
                savePos()
                setImage(if (idleMode) 5 else 1)
                scheduleWalk()
            }
        })
        walkAnim = a
        a.start()
    }

    private fun stopWalk() {
        mh.removeCallbacksAndMessages(T_WALK)
        val a = walkAnim
        if (walking && a != null) {
            walkAnim = null
            walking = false
            a.cancel()
            charView.scaleX = 1f
            savePos()
            setImage(if (idleMode) 5 else 1)
        }
    }

    // ============ 터치 처리 (b, c, d) ============
    private fun onCharTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopWalk()
                downX = e.rawX; downY = e.rawY
                grabDX = e.rawX - posX; grabDY = e.rawY - posY
                moved = false
                longPressed = false
                mh.postAtTime({ longPressed = true; startDrag() }, T_LONG,
                    SystemClock.uptimeMillis() + prefs.getInt("long_ms", 1000).toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressed) {
                    moveTo(e.rawX, e.rawY)
                    updateBarState(e.rawX, e.rawY)
                } else if (hypot(e.rawX - downX, e.rawY - downY) > ViewConfiguration.get(this).scaledTouchSlop) {
                    moved = true
                    mh.removeCallbacksAndMessages(T_LONG)
                }
            }
            MotionEvent.ACTION_UP -> {
                mh.removeCallbacksAndMessages(T_LONG)
                if (longPressed) {
                    updateBarState(e.rawX, e.rawY)
                    endDrag()
                } else if (!moved) {
                    onTap()
                } else {
                    toA()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                mh.removeCallbacksAndMessages(T_LONG)
                if (longPressed) endDrag() else toA()
            }
        }
    }

    private fun onTap() {
        if (tapPending) {
            mh.removeCallbacksAndMessages(T_TAP)
            tapPending = false
            onDoubleTap()
        } else {
            tapPending = true
            mh.postAtTime({ tapPending = false; onSingleTap() }, T_TAP,
                SystemClock.uptimeMillis() + ViewConfiguration.getDoubleTapTimeout())
        }
    }

    // b
    private fun onSingleTap() {
        val pkg = activeNotifPkg
        if (pkg != null) openNotification(pkg, activeNotifIntent)        // g 말풍선 중 터치 → 알림 앱으로 이동
        val base = max(loopMs(2), 500).toLong()
        showEvent(2, null, base, Cfg.effect(prefs, "b"))
    }

    // c
    private fun onDoubleTap() {
        vibrate(50)
        cancelEvent()
        busy = true
        mh.removeCallbacksAndMessages(T_H)
        resetIdle()
        setImage(1)
        startEffect(Cfg.effect(prefs, "c"), true)
        openMenu()
    }

    // d
    private fun startDrag() {
        cancelEvent()
        mh.removeCallbacksAndMessages(T_H)
        resetIdle()
        vibrate(50)
        dragging = true
        overBar = false
        busy = true
        setImage(3)
        startEffect(Cfg.effect(prefs, "d"), false)
        showBar()
        overSide = 0
        if (walkOn()) showSideBars()
    }

    private fun moveTo(rawX: Float, rawY: Float) {
        val s = charLp.width
        posX = (rawX - grabDX).toInt().coerceIn(0, max(0, screenW - s))
        posY = (rawY - grabDY).toInt().coerceIn(0, max(0, screenH - s))
        applyPos()
        if (bubbleAdded) refreshBubble()
    }

    private fun barHeight() = dp(prefs.getInt("bar_dp", 90))

    private fun updateBarState(rawX: Float, rawY: Float) {
        val over = rawY >= screenH - barHeight()
        if (over != overBar) {
            overBar = over
            barView?.setBackgroundColor(if (over) 0x99FF3B30.toInt() else 0x99000000.toInt())
            if (over) showBubble(dText()) else hideBubble()
        }
        if (sideL != null) {
            val bw = barHeight()
            val sd = if (over) 0 else if (rawX <= bw) 1 else if (rawX >= screenW - bw) 2 else 0
            if (sd != overSide) {
                overSide = sd
                sideL?.setBackgroundColor(if (sd == 1) 0x992F7BF5.toInt() else 0x99000000.toInt())
                sideR?.setBackgroundColor(if (sd == 2) 0x992F7BF5.toInt() else 0x99000000.toInt())
            }
        }
    }

    private fun endDrag() {
        dragging = false
        val exit = overBar
        val sd = overSide
        hideBar()
        hideSideBars()
        overBar = false
        overSide = 0
        if (exit) {
            exitCharacter()
        } else if (sd != 0 && walkOn()) {
            setParked(true)
            posX = if (sd == 1) 0 else max(0, screenW - charLp.width)
            applyPos()
            cancelEvent()
            savePos()
            busy = false
            toA()
            Toast.makeText(this, "가장자리에서 쉬어요. 다시 꾹 눌러 끌어내면 이동해요", Toast.LENGTH_SHORT).show()
        } else {
            setParked(false)
            cancelEvent()
            savePos()
            busy = false
            toA()
        }
    }

    private fun exitCharacter() {
        savePos()
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("exited", true)
            )
        } catch (e: Exception) { }
        stopSelf()
    }

    private fun showBar() {
        if (barAdded) return
        val v = View(this).apply { setBackgroundColor(0x99000000.toInt()) }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, barHeight(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }
        wm.addView(v, lp)
        barView = v
        barAdded = true
    }

    private fun showSideBars() {
        if (sideL != null) return
        val w = barHeight()
        val h = max(1, screenH - barHeight())
        fun make(left: Boolean): View {
            val v = View(this).apply { setBackgroundColor(0x99000000.toInt()) }
            val lp = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (left) 0 else screenW - w
                y = 0
            }
            wm.addView(v, lp)
            return v
        }
        sideL = make(true)
        sideR = make(false)
    }

    private fun hideSideBars() {
        sideL?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        sideR?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        sideL = null
        sideR = null
        overSide = 0
    }

    private fun hideBar() {
        if (barAdded) {
            try { wm.removeView(barView) } catch (e: Exception) { }
            barAdded = false
            barView = null
        }
    }

    // ============ c: 메뉴 ============
    private fun menuItem(icon: Drawable?, emoji: String, label: String, onClick: () -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            minimumWidth = dp(64)
            setOnClickListener { onClick() }
        }
        if (icon != null) {
            col.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(38), dp(38)))
        } else {
            col.addView(TextView(this).apply { text = emoji; textSize = 26f; gravity = Gravity.CENTER })
        }
        col.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(0xFF222222.toInt())
            gravity = Gravity.CENTER
            maxLines = 1
            maxWidth = dp(84)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        return col
    }

    private fun appItem(key: String): View {
        val pkg = prefs.getString(key, null)
        if (pkg.isNullOrEmpty()) {
            return menuItem(null, "➕", "앱 선택") {
                closeMenu()
                Toast.makeText(this, "상세 설정에서 바로가기 앱을 골라 주세요", Toast.LENGTH_LONG).show()
                toA()
            }
        }
        return menuItem(Apps.icon(this, pkg), "📱", Apps.label(this, pkg)) {
            closeMenu()
            if (!Apps.launch(this, pkg)) {
                Toast.makeText(this, "앱을 열 수 없어요", Toast.LENGTH_SHORT).show()
            }
            toA()
        }
    }

    private fun openMenu() {
        val row = object : LinearLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_OUTSIDE) mh.post { onMenuOutside() }
                return super.dispatchTouchEvent(ev)
            }
        }
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.setPadding(dp(6), dp(6), dp(6), dp(6))
        row.background = roundBg(0xF2FFFFFF.toInt(), 0xFF555555.toInt(), 16)

        if (prefs.getInt("menu_mode", 1) == 1) {
            row.addView(menuItem(null, "☀️", "날씨") { startWeather() })
            row.addView(appItem("app1"))
            row.addView(menuItem(null, "🎲", "O/X 룰렛") { startRoulette() })
        } else {
            row.addView(appItem("app1"))
            row.addView(appItem("app2"))
            row.addView(appItem("app3"))
        }

        val lp = overlayLp(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        )
        placeAbove(row, lp)
        wm.addView(row, lp)
        menuView = row
        menuLp = lp
        menuAdded = true
    }

    private fun closeMenu() {
        if (menuAdded) {
            try { wm.removeView(menuView) } catch (e: Exception) { }
            menuAdded = false
            menuView = null
        }
    }

    private fun onMenuOutside() {
        if (menuAdded) {
            closeMenu()
            toA()
        }
    }

    // ---- 룰렛 ----
    private fun startRoulette() {
        val r = if (Random.nextBoolean()) "O" else "X"
        val u = user()
        val result = pick("결과는 $r 입니다 ${u}님", "결과는 $r 이야 $u!")
        showEvent(1, "3", 60_000, 0)
        postEv(1000) { showBubble("2") }
        postEv(2000) { showBubble("1") }
        postEv(3000) { showBubble(result) }
        postEv(6000) { toA() }
    }

    // ---- 날씨 ----
    private fun startWeather() {
        showEvent(1, pick("날씨를 확인하고 있어요...", "날씨 확인 중..."), 15_000, 0)
        val serial = eventSerial
        val city = prefs.getString("city", "서울").orEmpty().ifBlank { "서울" }
        Thread {
            val msg = try {
                fetchWeather(city)
            } catch (e: Exception) {
                "날씨를 가져오지 못했어요. 인터넷과 도시 이름을 확인해 주세요."
            }
            mh.post { if (running && serial == eventSerial) showEvent(1, msg, 5000, 0) }
        }.start()
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 6000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun fetchWeather(city: String): String {
        var lat = prefs.getString("geo_lat_$city", null)
        var lon = prefs.getString("geo_lon_$city", null)
        if (lat == null || lon == null) {
            val g = JSONObject(
                httpGet("https://geocoding-api.open-meteo.com/v1/search?name=" +
                    URLEncoder.encode(city, "UTF-8") + "&count=1&language=ko")
            ).getJSONArray("results").getJSONObject(0)
            lat = g.getDouble("latitude").toString()
            lon = g.getDouble("longitude").toString()
            prefs.edit().putString("geo_lat_$city", lat).putString("geo_lon_$city", lon).apply()
        }
        val w = JSONObject(
            httpGet("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true")
        ).getJSONObject("current_weather")
        val t = Math.round(w.getDouble("temperature"))
        val desc = weatherText(w.getInt("weathercode"))
        return pick("$city 현재 ${t}°C, $desc 입니다.", "$city 지금 ${t}°C, $desc!")
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "맑음"
        1, 2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        in 61..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        85, 86 -> "눈 소나기"
        in 95..99 -> "천둥번개"
        else -> "알 수 없음"
    }

    // ============ e: 배터리 ============
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!inited) return
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            if (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) {
                batteryArmed = true       // 충전을 시작해야 다시 경고 가능
                return
            }
            val t = prefs.getInt("battery_pct", 20)
            if (pct <= t && batteryArmed) {
                batteryArmed = false
                vibrate(60)
                val u = user()
                showEvent(
                    4,
                    pick("${u}님 배터리 잔량이 ${t}% 정도만 남았습니다.", "$u! 배터리가 ${t}%밖에 안남았어.."),
                    3000, Cfg.effect(prefs, "e")
                )
            }
        }
    }

    // ============ f: 같은 앱 오래 사용 ============
    private fun scheduleUsage() {
        mh.postAtTime({ pollUsage(); scheduleUsage() }, T_USAGE, SystemClock.uptimeMillis() + 20_000)
    }

    @Suppress("DEPRECATION")
    private fun pollUsage() {
        val mgr = usm ?: return
        if (!Perms.usage(this)) return
        val now = System.currentTimeMillis()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            fgPkg = null
            screenWasOff = true
            lastQuery = 0L
            return
        }
        if (screenWasOff) {
            screenWasOff = false
            screenOnAt = now
        }
        val begin = if (lastQuery == 0L) now - 3_600_000L else lastQuery - 1000L
        var latest: String? = null
        var latestTime = 0L
        try {
            val events = mgr.queryEvents(begin, now)
            val ev = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    latest = ev.packageName
                    latestTime = ev.timeStamp
                }
            }
        } catch (e: Exception) {
            return
        }
        lastQuery = now
        if (latest != null && latest != fgPkg) {
            fgPkg = latest
            fgSince = max(latestTime, screenOnAt)
            nextWarnAt = fgSince + 30 * 60_000L
        }
        val p = fgPkg ?: return
        if (p == packageName || p == "com.android.systemui" || p in homePkgs) return
        if (now >= nextWarnAt) {
            while (nextWarnAt <= now) nextWarnAt += 30 * 60_000L
            val u = user()
            showEvent(
                1,
                pick("${u}님? 슬슬 휴식을 취하시는게 좋을것 같습니다.", "$u! 너무 오래 하는거 아니야?"),
                3000, Cfg.effect(prefs, "f")
            )
        }
    }

    // ============ g: 알림 ============
    fun onNotification(pkg: String, appLabel: String, pi: PendingIntent? = null) {
        if (!running || !inited) return
        val u = user()
        val honorific = prefs.getBoolean("honorific", true)
        val sb = SpannableStringBuilder(if (honorific) "${u}님 " else "$u! ")
        val st = sb.length
        sb.append(appLabel)
        sb.setSpan(ForegroundColorSpan(0xFF0B8FE0.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(if (honorific) "에서 문자가 왔습니다." else "에서 문자가 왔어!")
        showEvent(if (File(filesDir, "img_6").exists()) 6 else 1, sb, 5000, Cfg.effect(prefs, "g"))
        activeNotifPkg = pkg
        activeNotifIntent = pi
    }

    private fun openNotification(pkg: String, pi: PendingIntent?) {
        if (pi != null && prefs.getInt("notif_open", 0) == 0) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = android.app.ActivityOptions.makeBasic()
                    opts.setPendingIntentBackgroundActivityStartMode(
                        android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return
            } catch (e: Exception) { }
        }
        Apps.launch(this, pkg)   // 실패하면 앱만 열기
    }

    // ============ 진동 ============
    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        if (v.hasVibrator()) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}