package io.github.ninetailsflip.lock

import android.content.Context
import life.furrer.nine.core.NineSurface
import life.furrer.nine.core.settings.NineSettingsActivity
import life.furrer.nine.core.settings.SettingsScreen

/** Lock's own look (font, hint bar) and about: the page every Nine app has. Its settings live
 *  in device-protected storage, which the lock screen can read before the first unlock. */
class LookActivity : NineSettingsActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.createDeviceProtectedStorageContext())
    }

    override fun buildAppSections(screen: SettingsScreen, surface: NineSurface) {}
}
