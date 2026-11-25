package io.github.ninetailsflip.lock

import android.app.Application
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import io.github.ninetailsflip.update.SetupChoices
import life.furrer.nine.core.KeyHelp
import life.furrer.nine.core.Nine
import life.furrer.nine.core.NineApp
import life.furrer.nine.core.NineSurface

/**
 * Lock's process: a persistent system app, started at boot (before the first unlock too). It
 * watches the keyguard and puts [LockActivity] over it whenever the phone locks, so the lock
 * screen is already there when the screen comes on.
 */
class LockApp : Application() {

    // Direct Boot: before the first unlock only device-protected storage is readable, so Lock
    // keeps everything (its look, the Nine prefs) there.
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.createDeviceProtectedStorageContext())
    }

    override fun onCreate() {
        super.onCreate()
        Nine.init(
            NineApp(
                id = "lock",
                name = "Lock",
                tagline = "The lock screen",
                surfaces = listOf(
                    NineSurface(
                        id = "",
                        name = "Lock",
                        introText = "The lock screen: the time, notifications and music, and the PIN typed straight on the keypad.",
                        keyMap = listOf(
                            KeyHelp("Digits", "Type the PIN"),
                            KeyHelp("OK", "Unlock, or open the chosen notification"),
                            KeyHelp("Up / Down", "Choose a notification"),
                            KeyHelp("Left", "Dismiss a notification; on the music, the previous track"),
                            KeyHelp("Right", "On the music, the next track"),
                            KeyHelp("Back", "Delete a digit; with nothing typed, the screen off"),
                            KeyHelp("*", "Menu: flashlight, camera, emergency call"),
                        ),
                    ),
                ),
                websiteUrl = "https://github.com/NineTailsFlip",
                sourceUrl = "https://github.com/NineTailsFlip/lock9",
                trackerUrl = "https://github.com/NineTailsFlip/lock9/issues",
                settingsActivity = LookActivity::class.java,
            ),
        )
        runCatching { SetupChoices.adopt(this) }
        grantNotificationAccess()
        watchKeyguard()
    }

    private fun watchKeyguard() {
        val km = getSystemService(KeyguardManager::class.java) ?: return
        try {
            km.addKeyguardLockedStateListener(mainExecutor) { locked -> onKeyguard(locked) }
        } catch (e: Exception) {
            Log.w(TAG, "Can't watch the keyguard: the stock lock screen stays", e)
            return
        }
        if (km.isKeyguardLocked) onKeyguard(true)
    }

    private fun onKeyguard(locked: Boolean) {
        Log.d(TAG, "keyguard ${if (locked) "locked" else "unlocked"}")
        if (locked) show(this) else LockActivity.current?.onUnlocked()
    }

    /** Lock reads the notifications to show them; a system app may grant itself the access. */
    private fun grantNotificationAccess() {
        val component = ComponentName(this, LockListener::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val granted = runCatching {
            nm.javaClass.getMethod("setNotificationListenerAccessGranted", ComponentName::class.java, Boolean::class.javaPrimitiveType)
                .invoke(nm, component, true)
        }.isSuccess
        if (!granted) runCatching {
            val key = "enabled_notification_listeners"
            val now = Settings.Secure.getString(contentResolver, key).orEmpty().split(':').filter { it.isNotEmpty() }
            val flat = component.flattenToString()
            if (flat !in now) Settings.Secure.putString(contentResolver, key, (now + flat).joinToString(":"))
        }.onFailure { Log.w(TAG, "No notification access: the lock screen shows none", it) }
    }

    companion object {
        private const val TAG = "Lock"

        fun show(context: Context) {
            try {
                context.startActivity(
                    Intent(context, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't show the lock screen: the stock one stays", e)
            }
        }
    }
}
