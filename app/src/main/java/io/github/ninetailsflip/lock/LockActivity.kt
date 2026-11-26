package io.github.ninetailsflip.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.HintBar
import life.furrer.nine.core.ui.MenuItem
import life.furrer.nine.core.ui.MenuSheet
import life.furrer.nine.core.ui.NineActivity
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.RowBinder
import life.furrer.nine.core.ui.RowListView
import life.furrer.nine.core.ui.dp
import java.util.concurrent.Executors

/**
 * The lock screen: the time, date, next alarm, battery and carrier at the top, the music and the
 * notifications under them, and the PIN typed straight on the keypad. Shown over the keyguard
 * (see docs/FEATURES.md for how unlocking works and what happens if this isn't here).
 */
class LockActivity : NineActivity() {

    /** What the keypad is typing into. */
    private enum class Entry { PIN, SIM_PIN, SIM_PUK, SIM_NEW_PIN }

    private var entry = Entry.PIN
    private val typed = StringBuilder()
    private var simPuk: String? = null
    private var message: String? = null
    private var throttledUntil = 0L
    private var checking = false
    /** The notification to open once unlocked (OK on it with the PIN still to type). */
    private var pendingOpen: LockNotification? = null
    private var torchOn = false
    /** Home was pressed over another app on the keyguard (the camera): home follows the unlock. */
    private var thenHome = false
    /** An app over the keyguard asked for the phone to be unlocked (the camera's gallery, say). */
    private var asked = false

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var pinLine: TextView
    private lateinit var rowListView: RowListView
    private lateinit var hintBar: HintBar
    private lateinit var menuSheet: MenuSheet

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var rows: List<Row> = emptyList()

    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    private val binder = object : RowBinder {
        override fun rowCount() = rows.size
        override fun createRowView(context: Context): View = TwoLineRowView(context)
        override fun bind(view: View, index: Int, selected: Boolean) = (view as TwoLineRowView).bind(rows[index], selected)
        override fun rowHeight(index: Int) = TWO_LINE_ROW_DP
    }

    // Direct Boot, as LockApp: device-protected storage.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.createDeviceProtectedStorageContext())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) {
            finish()
            return
        }
        val side = dp(Dimens.SIDE_PADDING_DP)
        clock = TextView(this).apply {
            textSize = 56f
            setTextColor(Palette.WHITE)
            setPadding(side, dp(8), side, 0)
        }
        date = TextView(this).apply {
            textSize = 16f
            setTextColor(Palette.GRAY_DIM)
            setPadding(side, 0, side, 0)
        }
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Palette.GRAY_MUTED)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(side, dp(4), side, dp(8))
        }
        pinLine = TextView(this).apply {
            textSize = 22f
            setTextColor(Palette.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, dp(6), side, dp(6))
        }
        rowListView = RowListView(this).apply { setBinder(binder) }
        hintBar = HintBar(this)
        menuSheet = MenuSheet(this)
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Palette.BLACK)
                addView(
                    LinearLayout(this@LockActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(clock)
                        addView(date)
                        addView(status)
                        addView(rowListView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                        addView(pinLine)
                        addView(hintBar)
                    },
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                addView(menuSheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            },
        )
        LockFeed.onChange = { runOnUiThread { render() } }
        takeRequest(intent)
        render()
    }

    /** The system shows this screen in place of the stock keyguard (android_vendor_thor2's patch
     *  0009), saying why: Home over an app on the keyguard, or an app asking for the unlock. */
    private fun takeRequest(intent: Intent?) {
        if (intent?.action != SHOW_ACTION) return
        if (intent.getBooleanExtra(EXTRA_THEN_HOME, false)) thenHome = true
        asked = intent.getBooleanExtra(EXTRA_DISMISS, false)
        if (asked) message = "unlock to continue"
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(ticker, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction("android.intent.action.SIM_STATE_CHANGED")
        }, RECEIVER_EXPORTED)
        LockFeed.reload()
        entry = when (Unlocker.simLock(this)) {
            Unlocker.SimLock.PIN -> Entry.SIM_PIN
            Unlocker.SimLock.PUK -> if (entry == Entry.SIM_NEW_PIN) entry else Entry.SIM_PUK
            Unlocker.SimLock.NONE -> if (entry == Entry.PIN) entry else Entry.PIN
        }
        render()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(ticker) }
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        LockFeed.onChange = null
        worker.shutdownNow()
        super.onDestroy()
    }

    /** The phone locked again while this was open (a second lock after a call, say). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        clearTyped()
        takeRequest(intent)
        render()
    }

    /** The keyguard went away (this screen's unlock, a trust agent, Android's own prompt). */
    fun onUnlocked() {
        val open = pendingOpen
        pendingOpen = null
        if (open != null) runCatching { open.open?.send() }
        if (thenHome && open == null) {
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        thenHome = false
        asked = false
        finish()
        overridePendingTransition(0, 0)
    }

    // ── Drawing ─────────────────────────────────────────────────────────────────────────────

    private fun render() {
        if (!::clock.isInitialized) return
        clock.text = Status.clock(this)
        date.text = Status.date()
        status.text = listOfNotNull(
            listOfNotNull(Status.nextAlarm(this), Status.battery(this)).joinToString("   "),
            Status.carrier(this),
            Status.ownerInfo(this),
        ).joinToString("\n")
        rows = buildRows()
        rowListView.notifyDataChanged()
        pinLine.text = pinText()
        pinLine.visibility = if (pinLine.text.isEmpty()) View.GONE else View.VISIBLE
        hintBar.setText(message ?: hint())
    }

    private fun buildRows(): List<Row> = buildList {
        LockFeed.mediaLine()?.let { add(Row(it, "ok play/pause · ←→ previous/next", media = true)) }
        val notifications = LockFeed.notifications
        notifications.forEach { n -> add(Row(n.title, listOfNotNull(n.app, n.text).joinToString(" · "), notification = n)) }
    }

    private fun pinText(): String {
        val dots = "●".repeat(typed.length)
        val wait = remainingThrottle()
        return when {
            wait > 0 -> "Try again in ${(wait + 999) / 1000} s"
            entry == Entry.SIM_PIN -> "SIM PIN: $dots"
            entry == Entry.SIM_PUK -> "SIM PUK: $dots"
            entry == Entry.SIM_NEW_PIN -> "New SIM PIN: $dots"
            checking -> "Checking…"
            typed.isNotEmpty() -> dots
            else -> ""
        }
    }

    private fun hint(): String = when {
        entry != Entry.PIN -> "type it · ok done · ‹ delete · * menu"
        !Unlocker.isSecure(this) -> "ok unlock · ↑↓ notifications · * menu"
        typed.isEmpty() && asked -> "type the PIN to continue · * menu · ‹ screen off"
        typed.isEmpty() -> "type the PIN · ok open · * menu · ‹ screen off"
        else -> "ok unlock · ‹ delete"
    }

    private fun remainingThrottle() = (throttledUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    // ── Keys ────────────────────────────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menuSheet.interceptKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        message = null
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit((keyCode - KeyEvent.KEYCODE_0).digitToChar())
            KeyEvent.KEYCODE_DEL -> deleteDigit()
            KeyEvent.KEYCODE_BACK -> if (typed.isNotEmpty()) deleteDigit() else screenOff()
            KeyEvent.KEYCODE_DPAD_UP -> rowListView.moveSelection(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> rowListView.moveSelection(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> sideways(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> ok()
            KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_MENU -> openMenu()
            KeyEvent.KEYCODE_CALL -> emergencyCall()
            else -> return super.onKeyDown(keyCode, event)
        }
        render()
        return true
    }

    private fun typeDigit(digit: Char) {
        if (checking || remainingThrottle() > 0) return
        if (entry == Entry.PIN && !Unlocker.isSecure(this)) return
        if (typed.length >= 16) return
        typed.append(digit)
        if (entry == Entry.PIN && Unlocker.autoConfirmLength(this) == typed.length) submit()
    }

    private fun deleteDigit() {
        if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
    }

    private fun clearTyped() {
        typed.setLength(0)
    }

    private fun sideways(direction: Int) {
        val row = rows.getOrNull(rowListView.selectedIndex) ?: return
        when {
            row.media -> LockFeed.skip(direction)
            direction < 0 && row.notification?.clearable == true -> LockFeed.dismiss(row.notification.key)
        }
    }

    private fun ok() {
        if (typed.isNotEmpty()) return submit()
        val row = rows.getOrNull(rowListView.selectedIndex)
        if (row?.media == true) return LockFeed.playPause()
        pendingOpen = row?.notification
        when {
            entry != Entry.PIN -> message = "type the SIM ${if (entry == Entry.SIM_PUK) "PUK" else "PIN"} first"
            !Unlocker.isSecure(this) -> unlock()
            !Unlocker.isPin(this) -> systemPrompt()
            else -> message = if (pendingOpen != null) "type the PIN: it opens once unlocked" else "type the PIN"
        }
    }

    private fun submit() {
        val text = typed.toString()
        when (entry) {
            Entry.PIN -> checkPin(text)
            Entry.SIM_PIN -> simPin(text, null)
            Entry.SIM_PUK -> {
                if (text.length < 8) {
                    message = "a PUK has 8 digits"
                    return
                }
                simPuk = text
                clearTyped()
                entry = Entry.SIM_NEW_PIN
            }
            Entry.SIM_NEW_PIN -> simPin(text, simPuk)
        }
    }

    private fun checkPin(pin: String) {
        if (pin.length < 4) {
            message = "a PIN has at least 4 digits"
            return
        }
        checking = true
        worker.execute {
            val result = Unlocker.check(this, pin)
            runOnUiThread {
                checking = false
                clearTyped()
                when (result) {
                    PinCheck.Ok -> unlock()
                    PinCheck.Wrong -> message = "wrong PIN"
                    is PinCheck.Throttled -> {
                        throttledUntil = SystemClock.elapsedRealtime() + result.timeoutMs
                        tickThrottle()
                    }
                    PinCheck.Unavailable -> systemPrompt()
                }
                render()
            }
        }
        render()
    }

    private fun tickThrottle() {
        render()
        if (remainingThrottle() > 0) handler.postDelayed(::tickThrottle, 1000)
    }

    private fun simPin(pin: String, puk: String?) {
        if (pin.length !in 4..8) {
            message = "a SIM PIN has 4 to 8 digits"
            return
        }
        checking = true
        worker.execute {
            val result = Unlocker.supplySim(this, pin, puk)
            runOnUiThread {
                checking = false
                clearTyped()
                simPuk = null
                entry = if (result.ok) Entry.PIN else if (puk != null) Entry.SIM_PUK else entry
                message = when {
                    result.ok -> "SIM unlocked"
                    result.attemptsLeft >= 0 -> "wrong: ${result.attemptsLeft} tries left"
                    else -> "the SIM didn't take it"
                }
                render()
            }
        }
    }

    /** Asks SystemUI to finish the keyguard; LockApp hears it go and closes this. If it hasn't
     *  gone in a while (a build without the SystemUI hook), Android's own prompt takes over. */
    private fun unlock() {
        Unlocker.dismissKeyguard(this)
        handler.postDelayed({
            if (!isFinishing && getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) systemPrompt()
        }, 1500)
    }

    /** Android's own PIN prompt: for a pattern or password, or when this screen's check can't run. */
    private fun systemPrompt() {
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = onUnlocked()
        })
    }

    // ── Menu and shortcuts ──────────────────────────────────────────────────────────────────

    private fun openMenu() {
        menuSheet.show(
            listOf(
                MenuItem(if (torchOn) "Flashlight off" else "Flashlight on") { toggleTorch() },
                MenuItem("Camera") { camera() },
                MenuItem("Emergency call") { emergencyCall() },
                MenuItem("Screen off") { screenOff() },
            ),
        )
    }

    private fun toggleTorch() {
        val cm = getSystemService(CameraManager::class.java) ?: return
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return run { message = "no flashlight" }
        runCatching { cm.setTorchMode(id, !torchOn) }
            .onSuccess { torchOn = !torchOn }
            .onFailure { message = "the flashlight is busy" }
        render()
    }

    private fun camera() = start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))

    private fun emergencyCall() = start(Intent("com.android.phone.EmergencyDialer.DIAL"))

    private fun start(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("Lock", "Couldn't start $intent", e)
            message = "that isn't on this phone"
            render()
        }
    }

    /** PowerManager.goToSleep (hidden; Lock holds DEVICE_POWER). */
    private fun screenOff() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        runCatching {
            pm.javaClass.getMethod("goToSleep", Long::class.javaPrimitiveType).invoke(pm, SystemClock.uptimeMillis())
        }.onFailure { Log.w("Lock", "Couldn't turn the screen off", it) }
    }

    companion object {
        /** What the system starts this with (patch 0009); only it and SystemUI may (the manifest). */
        const val SHOW_ACTION = "org.thor2.keyguard.action.SHOW"
        const val EXTRA_DISMISS = "org.thor2.keyguard.extra.DISMISS"
        const val EXTRA_THEN_HOME = "org.thor2.keyguard.extra.THEN_HOME"

        private var shown = java.lang.ref.WeakReference<LockActivity>(null)

        /** The lock screen showing now, for LockApp to close when the keyguard goes. */
        var current: LockActivity?
            get() = shown.get()
            set(value) {
                shown = java.lang.ref.WeakReference(value)
            }
    }
}
