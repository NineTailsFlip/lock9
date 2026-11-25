package io.github.ninetailsflip.lock

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** One notification as the lock screen shows it. [text] is null when its contents are hidden. */
data class LockNotification(
    val key: String,
    val app: String,
    val title: String,
    val text: String?,
    val clearable: Boolean,
    val open: PendingIntent?,
)

/** What a notification may show on the lock screen, from the phone's settings and the app's
 *  own visibility: nothing (left out), the app only ("contents hidden"), or everything. */
enum class Shown { NONE, APP_ONLY, ALL }

object LockRules {
    /** Pure, for the decision itself: the settings and the notification's facts in, what shows out. */
    fun shown(
        showOnLock: Boolean,
        allowPrivate: Boolean,
        showSilent: Boolean,
        visibility: Int,
        silent: Boolean,
        userUnlocked: Boolean,
    ): Shown = when {
        !showOnLock -> Shown.NONE
        visibility == Notification.VISIBILITY_SECRET -> Shown.NONE
        silent && !showSilent -> Shown.NONE
        !userUnlocked -> Shown.APP_ONLY
        visibility == Notification.VISIBILITY_PUBLIC -> Shown.ALL
        allowPrivate -> Shown.ALL
        else -> Shown.APP_ONLY
    }
}

/**
 * The notifications and media sessions the lock screen shows, kept by [LockListener] (a
 * notification listener Lock turns on for itself) and read by [LockActivity], which redraws
 * when [onChange] fires.
 */
object LockFeed {
    @Volatile var notifications: List<LockNotification> = emptyList()
        private set
    @Volatile var media: MediaController? = null
        private set
    var onChange: (() -> Unit)? = null

    private var listener: LockListener? = null

    internal fun attach(service: LockListener) {
        listener = service
        reload()
        watchMedia(service)
    }

    internal fun detach(service: LockListener) {
        if (listener === service) listener = null
        notifications = emptyList()
        media = null
        changed()
    }

    internal fun reload() {
        val service = listener ?: return
        val active = runCatching { service.activeNotifications }.getOrNull().orEmpty()
        val ranking = runCatching { service.currentRanking }.getOrNull()
        val context = service.applicationContext
        val resolver = context.contentResolver
        val showOnLock = Settings.Secure.getInt(resolver, "lock_screen_show_notifications", 1) == 1
        val allowPrivate = Settings.Secure.getInt(resolver, "lock_screen_allow_private_notifications", 1) == 1
        val showSilent = Settings.Secure.getInt(resolver, "lock_screen_show_silent_notifications", 1) == 1
        val unlocked = context.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        notifications = active
            .filter { !it.notification.isGroupSummary() && it.notification.category != Notification.CATEGORY_TRANSPORT }
            .sortedByDescending { it.postTime }
            .mapNotNull { sbn ->
                val silent = ranking?.let { r ->
                    val rank = NotificationListenerService.Ranking()
                    r.getRanking(sbn.key, rank) && rank.importance <= android.app.NotificationManager.IMPORTANCE_LOW
                } ?: false
                when (LockRules.shown(showOnLock, allowPrivate, showSilent, sbn.notification.visibility, silent, unlocked)) {
                    Shown.NONE -> null
                    Shown.APP_ONLY -> item(context, sbn, hidden = true)
                    Shown.ALL -> item(context, sbn, hidden = false)
                }
            }
        changed()
    }

    private fun Notification.isGroupSummary() = flags and Notification.FLAG_GROUP_SUMMARY != 0

    private fun item(context: Context, sbn: StatusBarNotification, hidden: Boolean): LockNotification {
        val pm = context.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        val extras = sbn.notification.extras
        val public = sbn.notification.publicVersion
        val title = if (hidden) public?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app
        else extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app
        val text = if (hidden) null
        else (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        return LockNotification(sbn.key, app, title, text, sbn.isClearable, sbn.notification.contentIntent)
    }

    fun dismiss(key: String) {
        runCatching { listener?.cancelNotification(key) }
    }

    // ── Media ───────────────────────────────────────────────────────────────────────────────

    private fun watchMedia(service: LockListener) {
        val sessions = service.getSystemService(MediaSessionManager::class.java) ?: return
        val component = ComponentName(service, LockListener::class.java)
        fun pick(controllers: List<MediaController>?) {
            media = controllers.orEmpty().firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: controllers.orEmpty().firstOrNull { it.metadata != null }
            changed()
        }
        runCatching {
            pick(sessions.getActiveSessions(component))
            sessions.addOnActiveSessionsChangedListener({ pick(it) }, component)
        }.onFailure { Log.w("Lock", "No media sessions", it) }
    }

    /** "▶ Title — Artist" for the media row, or null with nothing playing or paused. */
    fun mediaLine(): String? {
        val controller = media ?: return null
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        return (if (playing) "▶ " else "⏸ ") + title + (artist?.let { " — $it" } ?: "")
    }

    fun playPause() {
        val controller = media ?: return
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controller.transportControls.pause()
        else controller.transportControls.play()
    }

    fun skip(direction: Int) {
        val controls = media?.transportControls ?: return
        if (direction > 0) controls.skipToNext() else controls.skipToPrevious()
    }

    private fun changed() {
        onChange?.invoke()
    }
}

/** Lock's notification listener: keeps [LockFeed] current. Lock grants itself the access
 *  (LockApp), as a system app may. */
class LockListener : NotificationListenerService() {
    override fun onListenerConnected() = LockFeed.attach(this)
    override fun onListenerDisconnected() = LockFeed.detach(this)
    override fun onNotificationPosted(sbn: StatusBarNotification?) = LockFeed.reload()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = LockFeed.reload()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = LockFeed.reload()
}
