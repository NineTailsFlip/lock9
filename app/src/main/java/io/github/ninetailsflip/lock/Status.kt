package io.github.ninetailsflip.lock

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Process
import android.provider.Settings
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import java.util.Date
import java.util.Locale

/** The lines at the top of the lock screen: clock, date, next alarm, battery, carrier, owner info. */
object Status {
    fun clock(context: Context, now: Long = System.currentTimeMillis()): String =
        DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Date(now)).toString()

    fun date(now: Long = System.currentTimeMillis()): String =
        DateFormat.format("EEEE d MMMM", Date(now)).toString().replaceFirstChar { it.titlecase(Locale.getDefault()) }

    /** "⏰ Thu 07:30", or null with no alarm set. */
    fun nextAlarm(context: Context): String? {
        val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return null
        val pattern = if (DateFormat.is24HourFormat(context)) "EEE HH:mm" else "EEE h:mm a"
        return "⏰ " + DateFormat.format(pattern, Date(alarm.triggerTime))
    }

    /** "84 %", "84 % · charging, full in 40 min", "100 % · charged". */
    fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (!plugged) return "$percent %"
        if (status == BatteryManager.BATTERY_STATUS_FULL || percent >= 100) return "$percent % · charged"
        val remaining = context.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1
        val until = if (remaining > 0) ", full in ${formatDuration(remaining)}" else ""
        return "$percent % · charging$until"
    }

    fun formatDuration(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** The carrier, or what stands in its way. */
    fun carrier(context: Context): String {
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1) return "Airplane mode"
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return ""
        return when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "No SIM — emergency calls only"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "SIM locked: type its PIN"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "SIM blocked: type its PUK"
            else -> {
                val state = runCatching { tm.serviceState?.state }.getOrNull()
                val name = tm.networkOperatorName.orEmpty()
                when {
                    state == ServiceState.STATE_EMERGENCY_ONLY -> "Emergency calls only"
                    state == ServiceState.STATE_OUT_OF_SERVICE || name.isEmpty() -> "No service"
                    else -> name
                }
            }
        }
    }

    /** The owner info / lock screen message, if one is set (LockPatternUtils, hidden). */
    fun ownerInfo(context: Context): String? = runCatching {
        val utils = Class.forName("com.android.internal.widget.LockPatternUtils").getConstructor(Context::class.java).newInstance(context)
        val user = Process.myUid() / 100_000
        val enabled = utils.javaClass.getMethod("isOwnerInfoEnabled", Int::class.javaPrimitiveType).invoke(utils, user) as Boolean
        if (!enabled) null else (utils.javaClass.getMethod("getOwnerInfo", Int::class.javaPrimitiveType).invoke(utils, user) as String?)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
