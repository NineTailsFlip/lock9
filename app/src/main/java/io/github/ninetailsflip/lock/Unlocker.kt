package io.github.ninetailsflip.lock

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.telephony.TelephonyManager
import android.util.Log
import java.lang.reflect.InvocationTargetException

/** What checking a PIN came to. */
sealed class PinCheck {
    object Ok : PinCheck()
    object Wrong : PinCheck()
    /** Too many wrong ones: no more tries for [timeoutMs]. */
    data class Throttled(val timeoutMs: Int) : PinCheck()
    /** The check itself failed (not a wrong PIN): hand over to Android's own prompt. */
    object Unavailable : PinCheck()
}

/**
 * The screen lock, the way the stock bouncer handles it: the PIN checked by LockSettingsService
 * through LockPatternUtils (hidden; Lock is platform-signed and holds
 * ACCESS_KEYGUARD_SECURE_STORAGE), failed and successful attempts reported as the bouncer
 * reports them, and the keyguard finished by SystemUI (android_vendor_thor2's patch 0006).
 */
object Unlocker {
    private const val TAG = "Lock"
    const val UNLOCK_ACTION = "org.thor2.keyguard.action.UNLOCK"
    private const val SYSTEMUI = "com.android.systemui"

    private val userId: Int get() = Process.myUid() / 100_000

    private fun utils(context: Context): Any =
        Class.forName("com.android.internal.widget.LockPatternUtils").getConstructor(Context::class.java).newInstance(context)

    private fun call(target: Any, name: String, vararg args: Pair<Class<*>, Any?>): Any? =
        target.javaClass.getMethod(name, *args.map { it.first }.toTypedArray()).invoke(target, *args.map { it.second }.toTypedArray())

    fun isSecure(context: Context) = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** The lock is a PIN (rather than a pattern or password, which only Android's prompt takes). */
    fun isPin(context: Context): Boolean = runCatching {
        // LockPatternUtils.getCredentialTypeForUser: 3 is CREDENTIAL_TYPE_PIN.
        call(utils(context), "getCredentialTypeForUser", Int::class.javaPrimitiveType!! to userId) as Int == 3
    }.getOrDefault(true)

    /** How long the PIN is when Android's auto-confirm is on (unlock as the last digit is typed),
     *  else null. */
    fun autoConfirmLength(context: Context): Int? = runCatching {
        val utils = utils(context)
        val on = call(utils, "isAutoPinConfirmEnabled", Int::class.javaPrimitiveType!! to userId) as Boolean
        val length = call(utils, "getPinLength", Int::class.javaPrimitiveType!! to userId) as Int
        if (on && length >= 4) length else null
    }.getOrNull()

    /** Checks [pin]. Blocking (the check takes a moment): call it off the main thread. */
    fun check(context: Context, pin: String): PinCheck {
        val utils = try {
            utils(context)
        } catch (e: Exception) {
            Log.w(TAG, "No LockPatternUtils", e)
            return PinCheck.Unavailable
        }
        return try {
            val credentialClass = Class.forName("com.android.internal.widget.LockscreenCredential")
            val credential = credentialClass.getMethod("createPin", CharSequence::class.java).invoke(null, pin)
            val progress = Class.forName("com.android.internal.widget.LockPatternUtils\$CheckCredentialProgressCallback")
            val ok = utils.javaClass.getMethod("checkCredential", credentialClass, Int::class.javaPrimitiveType, progress)
                .invoke(utils, credential, userId, null) as Boolean
            report(context, utils, ok)
            if (ok) PinCheck.Ok else PinCheck.Wrong
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            if (cause.javaClass.simpleName == "RequestThrottledException") {
                val timeout = runCatching { cause.javaClass.getMethod("getTimeoutMs").invoke(cause) as Int }.getOrDefault(30_000)
                PinCheck.Throttled(timeout)
            } else {
                Log.w(TAG, "checkCredential failed", cause)
                PinCheck.Unavailable
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkCredential failed", e)
            PinCheck.Unavailable
        }
    }

    /** What the bouncer reports after each attempt: device admins count failures for their wipe
     *  policy, trust agents hear of the unlock. */
    private fun report(context: Context, utils: Any, ok: Boolean) {
        runCatching {
            call(utils, if (ok) "reportSuccessfulPasswordAttempt" else "reportFailedPasswordAttempt", Int::class.javaPrimitiveType!! to userId)
        }.onFailure { Log.w(TAG, "Couldn't report the attempt", it) }
        runCatching {
            val trust = context.getSystemService("trust") ?: return
            call(trust, "reportUnlockAttempt", Boolean::class.javaPrimitiveType!! to ok, Int::class.javaPrimitiveType!! to userId)
        }.onFailure { Log.w(TAG, "Couldn't tell the trust agents", it) }
    }

    /** Asks SystemUI to finish the keyguard: after a correct PIN, or right away with no lock. */
    fun dismissKeyguard(context: Context) {
        context.sendBroadcast(Intent(UNLOCK_ACTION).setPackage(SYSTEMUI))
    }

    // ── SIM ─────────────────────────────────────────────────────────────────────────────────

    enum class SimLock { NONE, PIN, PUK }

    fun simLock(context: Context): SimLock = when (context.getSystemService(TelephonyManager::class.java)?.simState) {
        TelephonyManager.SIM_STATE_PIN_REQUIRED -> SimLock.PIN
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> SimLock.PUK
        else -> SimLock.NONE
    }

    /** The result of a SIM PIN or PUK: ok, or the attempts left (-1: unknown). */
    data class SimResult(val ok: Boolean, val attemptsLeft: Int)

    /** TelephonyManager.supplyIccLockPin / supplyIccLockPuk (@SystemApi, MODIFY_PHONE_STATE). */
    fun supplySim(context: Context, pin: String, puk: String? = null): SimResult = try {
        val tm = context.getSystemService(TelephonyManager::class.java)!!
        val result = if (puk == null) call(tm, "supplyIccLockPin", String::class.java to pin)
        else call(tm, "supplyIccLockPuk", String::class.java to puk, String::class.java to pin)
        val code = result!!.javaClass.getMethod("getResult").invoke(result) as Int
        val left = result.javaClass.getMethod("getAttemptsRemaining").invoke(result) as Int
        SimResult(code == 0, left)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't unlock the SIM", e)
        SimResult(false, -1)
    }
}
