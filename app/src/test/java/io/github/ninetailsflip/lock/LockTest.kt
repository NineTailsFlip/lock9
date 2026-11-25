package io.github.ninetailsflip.lock

import android.app.KeyguardManager
import android.app.Notification
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class LockTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun shown(
        showOnLock: Boolean = true,
        allowPrivate: Boolean = true,
        showSilent: Boolean = true,
        visibility: Int = Notification.VISIBILITY_PRIVATE,
        silent: Boolean = false,
        unlocked: Boolean = true,
    ) = LockRules.shown(showOnLock, allowPrivate, showSilent, visibility, silent, unlocked)

    @Test
    fun `notifications follow the lock screen settings and the app's visibility`() {
        assertEquals(Shown.ALL, shown())
        assertEquals(Shown.NONE, shown(showOnLock = false))
        assertEquals(Shown.NONE, shown(visibility = Notification.VISIBILITY_SECRET))
        assertEquals(Shown.APP_ONLY, shown(allowPrivate = false))
        assertEquals(Shown.ALL, shown(allowPrivate = false, visibility = Notification.VISIBILITY_PUBLIC))
        assertEquals(Shown.NONE, shown(showSilent = false, silent = true))
        assertEquals(Shown.ALL, shown(showSilent = false, silent = false))
        assertEquals("before the first unlock: no contents", Shown.APP_ONLY, shown(unlocked = false, visibility = Notification.VISIBILITY_PUBLIC))
    }

    @Test
    fun `durations read in minutes, then hours`() {
        assertEquals("1 min", Status.formatDuration(1_000))
        assertEquals("40 min", Status.formatDuration(40 * 60_000L))
        assertEquals("1 h 30 min", Status.formatDuration(90 * 60_000L))
    }

    @Test
    fun `without the keyguard locked it doesn't stay`() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setKeyguardLocked(false)
        val activity = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
    }

    private fun texts(activity: LockActivity): List<String> {
        fun collect(v: View, out: MutableList<String>) {
            when (v) {
                is TextView -> out += v.text.toString()
                is ViewGroup -> (0 until v.childCount).forEach { collect(v.getChildAt(it), out) }
            }
        }
        return mutableListOf<String>().also { collect(activity.window.decorView, it) }
    }

    @Test
    fun `digits type the PIN as dots, Back deletes them`() {
        val km = shadowOf(context.getSystemService(KeyguardManager::class.java))
        km.setKeyguardLocked(true)
        km.setIsDeviceSecure(true)
        val activity = Robolectric.buildActivity(LockActivity::class.java).setup().get()
        assertFalse(activity.isFinishing)
        fun press(code: Int) = activity.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code))
        press(KeyEvent.KEYCODE_1)
        press(KeyEvent.KEYCODE_2)
        press(KeyEvent.KEYCODE_3)
        assertTrue(texts(activity).contains("●●●"))
        press(KeyEvent.KEYCODE_BACK)
        assertTrue(texts(activity).contains("●●"))
        assertFalse("Back with digits typed deletes, it doesn't leave", activity.isFinishing)
    }
}
