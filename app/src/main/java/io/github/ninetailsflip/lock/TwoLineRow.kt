package io.github.ninetailsflip.lock

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import life.furrer.nine.core.ui.Dimens
import life.furrer.nine.core.ui.Palette
import life.furrer.nine.core.ui.dp

/** A row of the lock screen's list: the music, or a notification. */
class Row(val title: String, val meta: String, val media: Boolean = false, val notification: LockNotification? = null)

/** Height of a [TwoLineRowView] in dp, for a RowBinder. */
const val TWO_LINE_ROW_DP = 48

/** A list row the way Nine apps draw them: a title over a dim line of details; the selected one
 *  white, the rest gray. */
class TwoLineRowView(context: Context) : LinearLayout(context) {
    private val title = TextView(context).apply {
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val meta = TextView(context).apply {
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Palette.GRAY_MUTED)
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        val side = context.dp(Dimens.SIDE_PADDING_DP)
        setPadding(side, 0, side, 0)
        addView(title)
        addView(meta)
    }

    fun bind(row: Row, selected: Boolean) {
        title.text = row.title
        title.setTextColor(if (selected) Palette.WHITE else Palette.GRAY_DIM)
        meta.text = row.meta
        meta.visibility = if (row.meta.isEmpty()) GONE else VISIBLE
    }
}
