package dev.phonerecall.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.assist.AssistActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * Draws a [WView] as RemoteViews. One set of views per size bucket; the launcher picks the largest that
 * fits (Android 12 responsive layouts), so resizing and folding need no update from Zeus. Colours are
 * resources with a values-night set, so the launcher's light or dark mode applies without a redraw.
 */
object WidgetViews {

    /** The buckets each widget offers. Pictures cost memory, so Venus keeps to three. */
    fun buckets(k: WKind): List<Bucket> = when (k) {
        WKind.TODAY, WKind.CHRONOS, WKind.AGORA -> Bucket.entries
        WKind.MUSIC -> listOf(Bucket.SMALL, Bucket.MEDIUM)
        WKind.DELPHI, WKind.HERMES -> listOf(Bucket.SMALL, Bucket.SHORT)
        WKind.TRIPS, WKind.DRIVE, WKind.MONEY, WKind.HEARTH, WKind.HEALTH, WKind.BRIEF, WKind.ROOM -> listOf(Bucket.SMALL, Bucket.SHORT, Bucket.MEDIUM)
        else -> listOf(Bucket.SMALL, Bucket.SHORT, Bucket.MEDIUM, Bucket.LARGE)
    }

    /** What the launcher does with [responsive]: the largest bucket that fits the widget's size. */
    fun pick(k: WKind, widthDp: Float, heightDp: Float): Bucket =
        buckets(k).filter { it.widthDp <= widthDp && it.heightDp <= heightDp }.maxByOrNull { it.widthDp * it.heightDp } ?: Bucket.SMALL

    fun responsive(c: Context, v: WView, plain: Boolean): RemoteViews =
        RemoteViews(buckets(v.kind).associate { SizeF(it.widthDp, it.heightDp) to views(c, v, it, plain) })

    fun views(c: Context, v: WView, b: Bucket, plain: Boolean): RemoteViews = when (v.kind) {
        WKind.DELPHI -> delphi(c, v, b, plain)
        WKind.HERMES -> hermes(c, v, b, plain)
        WKind.MUSIC -> music(c, v, b, plain)
        WKind.HEARTH -> hearth(c, v, b, plain)
        else -> card(c, v, b, plain)
    }

    // ---- taps ----

    private const val OPEN = 47
    private const val ACT = 48

    fun open(c: Context, dest: String): PendingIntent = PendingIntent.getActivity(
        c, OPEN,
        Intent(c, MainActivity::class.java).setData(Uri.parse("zeus-widget://open/$dest")).putExtra(MainActivity.EXTRA_DEST, dest)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun act(c: Context, action: String, what: String, extra: Intent.() -> Unit): PendingIntent = PendingIntent.getBroadcast(
        c, ACT, Intent(c, WidgetActions::class.java).setAction(action).setData(Uri.parse("zeus-widget://$what")).apply(extra),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun done(c: Context, id: Long) = act(c, WidgetActions.DONE, "task/$id") { putExtra(WidgetActions.EXTRA_ID, id) }
    fun musicTap(c: Context, action: String) = act(c, WidgetActions.MUSIC, "music/$action") { putExtra(WidgetActions.EXTRA_ACTION, action) }
    fun hearthTap(c: Context, act: String) = act(c, WidgetActions.HEARTH, "hestia/$act") { putExtra(WidgetActions.EXTRA_ACTION, act) }

    private fun listen(c: Context): PendingIntent = PendingIntent.getActivity(
        c, OPEN, Intent(c, AssistActivity::class.java).setData(Uri.parse("zeus-widget://listen")).putExtra(AssistActivity.EXTRA_LISTEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Hermes's command sheet: the keyboard up, or listening from the mic. */
    private fun sheet(c: Context, listen: Boolean): PendingIntent = PendingIntent.getActivity(
        c, OPEN, Intent(c, dev.phonerecall.hermes.HermesActivity::class.java).setData(Uri.parse("zeus-widget://hermes/" + if (listen) "listen" else "type"))
            .putExtra(dev.phonerecall.hermes.HermesActivity.EXTRA_LISTEN, listen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ---- pieces ----

    private fun dot(t: WTone) = when (t) {
        WTone.OK -> R.drawable.widget_dot_ok
        WTone.ACTIVE -> R.drawable.widget_dot_active
        WTone.ALERT -> R.drawable.widget_dot_alert
        WTone.QUIET -> R.drawable.widget_dot_quiet
    }

    private fun RemoteViews.show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)

    private fun row(c: Context, r: WRow, fallback: String): RemoteViews = RemoteViews(c.packageName, R.layout.widget_row).apply {
        setTextViewText(R.id.row_text, r.text)
        setTextViewText(R.id.row_meta, r.meta)
        show(R.id.row_meta, r.meta.isNotEmpty())
        if (r.tone == WTone.ALERT) setColor(R.id.row_meta, "setTextColor", R.color.widget_alert)
        when (r.mark) {
            Mark.NONE -> show(R.id.row_mark, false)
            Mark.DOT -> { setImageViewResource(R.id.row_mark, dot(r.tone)); setContentDescription(R.id.row_mark, null) }
            Mark.CHECK -> {
                setImageViewResource(R.id.row_mark, R.drawable.widget_check)
                setContentDescription(R.id.row_mark, "Mark done: ${r.text}")
                // His own local task: the tap finishes it. Agents' tasks never get a box.
                r.taskId?.let { setOnClickPendingIntent(R.id.row_mark, done(c, it)) }
            }
        }
        setOnClickPendingIntent(R.id.row_root, open(c, r.dest ?: fallback))
    }

    private fun fill(c: Context, rv: RemoteViews, body: Int, groups: List<WGroup>, fallback: String) {
        rv.removeAllViews(body)
        for (g in groups) {
            if (g.label.isNotEmpty()) rv.addView(body, RemoteViews(c.packageName, R.layout.widget_label).apply { setTextViewText(R.id.label_text, g.label) })
            if (g.rows.isEmpty()) rv.addView(body, RemoteViews(c.packageName, R.layout.widget_empty).apply { setTextViewText(R.id.empty_text, g.empty) })
            for (r in g.rows) rv.addView(body, row(c, r, fallback))
        }
    }

    /** A closet picture, downsampled to at most [max] px on its long side. Local files only. */
    fun bitmap(path: String, max: Int = 240): Bitmap? = runCatching {
        val f = File(path)
        if (!f.isFile) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= max) s *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    // ---- the three layouts ----

    private fun card(c: Context, v: WView, b: Bucket, plain: Boolean): RemoteViews = RemoteViews(c.packageName, R.layout.widget_card).apply {
        val small = b == Bucket.SMALL || b == Bucket.SHORT
        val pictures = v.pics.isNotEmpty()
        // The line under the title costs a row; on short widgets (and over pictures) it goes when rows need the room.
        val subShown = v.sub.isNotEmpty() && !(b == Bucket.SHORT && v.groups.any { it.rows.isNotEmpty() }) && !(pictures && b == Bucket.MEDIUM)
        val barShown = v.bar != null && !small
        val reserved = (if (subShown) Bucket.SUB_DP else 0f) + (if (barShown) Bucket.BAR_DP else 0f)
        setTextViewText(R.id.w_kicker, v.kicker(plain))
        setTextViewText(R.id.w_title, v.title)
        setInt(R.id.w_title, "setMaxLines", if (small || v.kind == WKind.TODAY || (pictures && b == Bucket.MEDIUM)) 1 else 2)
        if (small) setTextViewTextSize(R.id.w_title, TypedValue.COMPLEX_UNIT_SP, 15f)
        if (b == Bucket.FULL || b == Bucket.WIDE_FULL) setTextViewTextSize(R.id.w_title, TypedValue.COMPLEX_UNIT_SP, 22f)
        setTextViewText(R.id.w_sub, v.sub)
        show(R.id.w_sub, subShown)
        setInt(R.id.w_sub, "setMaxLines", if (small) 1 else 2)
        show(R.id.w_tone, v.tone != WTone.QUIET)
        setImageViewResource(R.id.w_tone, dot(v.tone))
        setTextViewText(R.id.w_foot, v.foot)
        show(R.id.w_foot, v.foot.isNotEmpty() && !small)
        show(R.id.w_bar, barShown)
        v.bar?.let { setProgressBar(R.id.w_bar, 100, it, false) }

        val pics = when (b) { Bucket.SMALL -> 0; Bucket.MEDIUM -> 3; else -> 4 }
        val shown = v.pics.take(pics).mapNotNull { p -> bitmap(p.path)?.let { p to it } }
        show(R.id.w_pics, shown.isNotEmpty())
        removeAllViews(R.id.w_pics)
        for ((p, bmp) in shown) {
            val tile = RemoteViews(c.packageName, if (p.fit) R.layout.widget_pic_fit else R.layout.widget_pic_crop)
            tile.setImageViewBitmap(R.id.pic_img, bmp)
            tile.setContentDescription(R.id.pic_img, p.label)
            p.bg?.let { tile.setInt(R.id.pic_fill, "setColorFilter", it) }
            tile.setViewLayoutHeight(R.id.pic_frame, if (b == Bucket.MEDIUM) 58f else 150f, TypedValue.COMPLEX_UNIT_DIP)
            addView(R.id.w_pics, tile)
        }

        if (b.wide) {
            val (left, right) = WidgetPlan.columns(v.groups, b, reserved)
            fill(c, this, R.id.w_body, left, v.kind.dest)
            fill(c, this, R.id.w_body2, right, v.kind.dest)
            show(R.id.w_body2, right.isNotEmpty())
        } else {
            fill(c, this, R.id.w_body, WidgetPlan.fit(v.groups, b, reserved), v.kind.dest)
            show(R.id.w_body2, false)
        }
        setOnClickPendingIntent(R.id.w_root, open(c, v.kind.dest))
    }

    private fun music(c: Context, v: WView, b: Bucket, plain: Boolean): RemoteViews =
        RemoteViews(c.packageName, if (b == Bucket.SMALL) R.layout.widget_music_small else R.layout.widget_music).apply {
            if (b != Bucket.SMALL) setTextViewText(R.id.w_kicker, v.kicker(plain))
            setTextViewText(R.id.w_title, v.title)
            setTextViewText(R.id.w_sub, v.sub)
            setImageViewResource(R.id.m_play, if (v.playing) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
            setContentDescription(R.id.m_play, if (v.playing) "Pause" else "Play")
            setOnClickPendingIntent(R.id.m_play, musicTap(c, if (v.playing) "pause" else "resume"))
            setOnClickPendingIntent(R.id.m_next, musicTap(c, "next"))
            if (b != Bucket.SMALL) setOnClickPendingIntent(R.id.m_prev, musicTap(c, "previous"))
            setOnClickPendingIntent(R.id.w_root, open(c, v.kind.dest))
        }

    /** Hestia's switches as tiles: two on the smallest sizes, four from medium. A tile tap switches it. */
    private fun hearth(c: Context, v: WView, b: Bucket, plain: Boolean): RemoteViews = RemoteViews(c.packageName, R.layout.widget_hearth).apply {
        setTextViewText(R.id.w_kicker, v.kicker(plain))
        setTextViewText(R.id.w_title, v.title)
        show(R.id.w_head, b != Bucket.SMALL)
        show(R.id.w_tone, v.tone != WTone.QUIET)
        setImageViewResource(R.id.w_tone, dot(v.tone))
        val rows = v.groups.flatMap { it.rows }.take(if (b == Bucket.MEDIUM) 4 else 2)
        removeAllViews(R.id.h_row1)
        removeAllViews(R.id.h_row2)
        show(R.id.h_row2, rows.size > 2)
        show(R.id.h_empty, rows.isEmpty())
        setTextViewText(R.id.h_empty, v.groups.firstOrNull()?.empty?.ifEmpty { null } ?: v.sub)
        for ((i, r) in rows.withIndex()) {
            val on = r.meta == "On"
            val tile = RemoteViews(c.packageName, R.layout.widget_tile)
            tile.setTextViewText(R.id.tile_name, r.text)
            tile.setTextViewText(R.id.tile_state, r.meta)
            tile.setInt(R.id.tile_root, "setBackgroundResource", if (on) R.drawable.widget_tile_on else R.drawable.widget_tile_off)
            tile.setColor(R.id.tile_name, "setTextColor", if (on) R.color.widget_on_saffron else R.color.widget_ink)
            tile.setColor(R.id.tile_state, "setTextColor", if (on) R.color.widget_on_saffron else if (r.tone == WTone.ALERT) R.color.widget_alert else R.color.widget_ink2)
            tile.setContentDescription(R.id.tile_root, "${r.text}, ${r.meta}" + if (r.act != null) ". Tap to switch ${if (on) "off" else "on"}" else "")
            tile.setOnClickPendingIntent(R.id.tile_root, r.act?.let { hearthTap(c, it) } ?: open(c, v.kind.dest))
            addView(if (i < 2) R.id.h_row1 else R.id.h_row2, tile)
        }
        // An odd tile out keeps its width: a blank one fills the row.
        if (rows.size == 1 || rows.size == 3) addView(if (rows.size == 1) R.id.h_row1 else R.id.h_row2, RemoteViews(c.packageName, R.layout.widget_tile_blank))
        setOnClickPendingIntent(R.id.w_root, open(c, v.kind.dest))
    }

    private fun delphi(c: Context, v: WView, b: Bucket, plain: Boolean): RemoteViews = RemoteViews(c.packageName, R.layout.widget_delphi).apply {
        setTextViewText(R.id.w_kicker, v.kicker(plain))
        show(R.id.w_kicker, b != Bucket.SMALL)
        setTextViewText(R.id.d_field, v.title)
        setOnClickPendingIntent(R.id.d_field, open(c, v.kind.dest))
        setOnClickPendingIntent(R.id.d_mic, listen(c))
        setOnClickPendingIntent(R.id.w_root, open(c, v.kind.dest))
    }

    /** Ask Zeus: the Delphi widget's shape, opening Hermes's sheet instead of the chat. */
    private fun hermes(c: Context, v: WView, b: Bucket, plain: Boolean): RemoteViews = RemoteViews(c.packageName, R.layout.widget_delphi).apply {
        setTextViewText(R.id.w_kicker, v.kicker(plain))
        show(R.id.w_kicker, b != Bucket.SMALL)
        setTextViewText(R.id.d_field, v.title)
        setContentDescription(R.id.d_mic, "Ask Zeus by voice")
        setOnClickPendingIntent(R.id.d_field, sheet(c, listen = false))
        setOnClickPendingIntent(R.id.d_mic, sheet(c, listen = true))
        setOnClickPendingIntent(R.id.w_root, sheet(c, listen = false))
    }
}

/** One provider per section. Each draws what Zeus last stored; none of them fetches anything. */
abstract class ZeusWidget(private val kind: WKind) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "widget-${kind.name.lowercase()}") { try { WidgetBus.redraw(app, kind) } finally { pending.finish() } }
    }
}

class TodayWidget : ZeusWidget(WKind.TODAY)
class ChronosWidget : ZeusWidget(WKind.CHRONOS)
class AgoraWidget : ZeusWidget(WKind.AGORA)
class DelphiWidget : ZeusWidget(WKind.DELPHI)
class VenusWidget : ZeusWidget(WKind.VENUS)
class MusicWidget : ZeusWidget(WKind.MUSIC)
class LemnosWidget : ZeusWidget(WKind.LEMNOS)
class CerberusWidget : ZeusWidget(WKind.CERBERUS)
class PantheonWidget : ZeusWidget(WKind.PANTHEON)
class MetronWidget : ZeusWidget(WKind.METRON)
class HermesWidget : ZeusWidget(WKind.HERMES)
class TripsWidget : ZeusWidget(WKind.TRIPS)
class DriveWidget : ZeusWidget(WKind.DRIVE)
class MoneyWidget : ZeusWidget(WKind.MONEY)
class HearthWidget : ZeusWidget(WKind.HEARTH)
class HealthWidget : ZeusWidget(WKind.HEALTH)
class BriefWidget : ZeusWidget(WKind.BRIEF)
class RoomWidget : ZeusWidget(WKind.ROOM)

/**
 * His taps on a widget that act directly: ticking one of his own local tasks, the music buttons, and
 * switching one of his Hestia lights or plugs (through [dev.phonerecall.hestia.HestiaCore.tap], which
 * refuses locks and every other forbidden kind).
 * Not exported; only the PendingIntents Zeus made can reach it. Agents' tasks are never done from here:
 * they need a signed confirm, so their rows open Pantheon.
 */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "widget-action") {
            try {
                when (intent.action) {
                    DONE -> {
                        val id = intent.getLongExtra(EXTRA_ID, -1L)
                        val core = dev.phonerecall.brain.BrainCore.get(app)
                        // Only an open task in his own local store; anything else is ignored.
                        if (id > 0 && core.tasks.load().open().any { it.id == id }) {
                            core.tasks.complete(id, System.currentTimeMillis())
                            core.changed()
                        }
                        WidgetBus.refreshFromDisk(app, force = true)
                    }
                    MUSIC -> {
                        val a = intent.getStringExtra(EXTRA_ACTION)?.takeIf { it in setOf("previous", "pause", "resume", "next") } ?: return@thread
                        val music = dev.phonerecall.music.MusicCore.get(app)
                        runCatching { music.control(a) }
                        runCatching { music.refresh() }
                        WidgetBus.music(app, music.now.value, force = true)
                    }
                    HEARTH -> {
                        val (id, want) = intent.getStringExtra(EXTRA_ACTION)?.split('|')?.takeIf { it.size == 2 && it[1] in setOf("on", "off") } ?: return@thread
                        WidgetBus.hearthTap(app, id, want == "on")
                    }
                }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val DONE = "dev.phonerecall.widget.DONE"
        const val MUSIC = "dev.phonerecall.widget.MUSIC"
        const val HEARTH = "dev.phonerecall.widget.HEARTH"
        const val EXTRA_ID = "id"
        const val EXTRA_ACTION = "action"
    }
}
