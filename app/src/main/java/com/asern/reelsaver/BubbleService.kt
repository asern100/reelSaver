package com.asern.reelsaver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs

/**
 * Floating buttons over TikTok / Instagram / Facebook / YouTube.
 * Tap ⬇ (video) or 🎵 (audio): opens the app's Share menu, taps "Copy link",
 * then downloads in the background — ReelSaver never opens.
 */
class BubbleService : AccessibilityService() {

    companion object {
        val SUPPORTED = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.aweme",
            "com.facebook.katana", "com.facebook.lite",
            "com.google.android.youtube",
        )
        // Share button labels (content description), most specific first
        private val SHARE_KEYS = listOf(
            "share", "partager", "partage", "مشاركة", "شارك", "send", "envoyer", "إرسال",
        )
        private val SHARE_EXCLUDE = listOf("repost", "republier", "إعادة نشر", "remix", "story", "stories")
        private val COPY_KEYS = listOf(
            "copy link", "copier le lien", "copier", "نسخ الرابط", "نسخ", "copy", "link",
        )
        private const val PREFS = "bubble"
        fun isEnabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)
        fun setEnabled(c: Context, on: Boolean) =
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())
    private var busy = false
    private var currentPkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in SUPPORTED) {
            currentPkg = pkg
            if (isEnabled(this)) show() else hide()
        } else if (!isTransient(pkg) && !busy) {
            currentPkg = null
            hide()
        }
    }

    /** Windows that pop up over the app (system UI, share sheets, keyboard) don't hide the buttons */
    private fun isTransient(pkg: String): Boolean {
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return true
        if ("intentresolver" in pkg) return true
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() {}

    override fun onDestroy() { hide(); super.onDestroy() }

    // ---------- Floating buttons ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundButton(icon: Int, color: Int) = ImageView(this).apply {
        setImageResource(icon)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(1), 0x55FFFFFF)
        }
        elevation = dp(4).toFloat()
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { topMargin = dp(10) }
    }

    private fun show() {
        if (bubble != null) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            alpha = 0.92f
        }
        val audioBtn = roundButton(R.drawable.ic_audio, 0xCC7B3FE4.toInt())
        val videoBtn = roundButton(R.drawable.ic_download, 0xCC1E8E5A.toInt())
        box.addView(audioBtn)
        box.addView(videoBtn)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = prefs.getInt("x", dp(10))
            // Default: right side, just above the like button
            y = prefs.getInt("y", (resources.displayMetrics.heightPixels * 0.27).toInt())
        }
        attachDrag(audioBtn, p) { onTap(audio = true) }
        attachDrag(videoBtn, p) { onTap(audio = false) }
        try {
            wm.addView(box, p)
            bubble = box; params = p
        } catch (_: Exception) { }
    }

    private fun hide() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) { } }
        bubble = null
    }

    /** Drag to move both buttons; a short tap triggers [onClick] */
    private fun attachDrag(v: View, p: WindowManager.LayoutParams, onClick: () -> Unit) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        p.x = (startX - dx).toInt().coerceAtLeast(0)   // gravity END: x grows leftwards
                        p.y = (startY + dy).toInt().coerceAtLeast(0)
                        bubble?.let { wm.updateViewLayout(it, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("x", p.x).putInt("y", p.y).apply()
                    else onClick()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Share → Copy link automation ----------

    private fun onTap(audio: Boolean) {
        if (busy) return
        busy = true
        bubble?.alpha = 0.35f
        val share = findShareButton()
        if (share == null) return fail()
        click(share)
        waitFor(tries = 12, find = ::findCopyLink) { copy ->
            if (copy == null) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return@waitFor fail()
            }
            click(copy)
            main.postDelayed({
                startActivity(Intent(this, QuickDownloadActivity::class.java)
                    .putExtra(QuickDownloadActivity.EXTRA_FROM_CLIPBOARD, true)
                    .putExtra(QuickDownloadActivity.EXTRA_AUDIO, audio)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                            or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
                done()
            }, 600)
        }
    }

    private fun fail() {
        Toast.makeText(this, R.string.bubble_failed, Toast.LENGTH_LONG).show()
        done()
    }

    private fun done() {
        busy = false
        bubble?.alpha = 0.92f
    }

    private fun waitFor(tries: Int, find: () -> AccessibilityNodeInfo?, then: (AccessibilityNodeInfo?) -> Unit) {
        main.postDelayed({
            val n = find()
            if (n != null || tries <= 0) then(n) else waitFor(tries - 1, find, then)
        }, 250)
    }

    private fun allNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val roots = windows.filter { it.root?.packageName?.toString() != packageName }.mapNotNull { it.root }
            .ifEmpty { listOfNotNull(rootInActiveWindow) }
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            out.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        roots.forEach { walk(it, 0) }
        return out
    }

    private fun label(n: AccessibilityNodeInfo) =
        ((n.contentDescription ?: "").toString() + " " + (n.text ?: "").toString()).trim().lowercase()

    private fun visible(n: AccessibilityNodeInfo): Boolean {
        val r = Rect(); n.getBoundsInScreen(r)
        return n.isVisibleToUser && r.width() > 0 && r.height() > 0
    }

    private fun findShareButton(): AccessibilityNodeInfo? {
        val screenW = resources.displayMetrics.widthPixels
        val candidates = allNodes().filter { n ->
            val l = label(n)
            l.isNotEmpty() && l.length < 40 && visible(n) &&
                SHARE_KEYS.any { it in l } && SHARE_EXCLUDE.none { it in l }
        }
        // Prefer the best keyword match, then buttons on the right side (reel action bar)
        return candidates.minWithOrNull(compareBy<AccessibilityNodeInfo>(
            { n -> SHARE_KEYS.indexOfFirst { it in label(n) } },
            { n -> val r = Rect(); n.getBoundsInScreen(r); if (r.centerX() > screenW / 2) 0 else 1 },
        ))
    }

    private fun findCopyLink(): AccessibilityNodeInfo? =
        allNodes().filter { n -> val l = label(n); l.isNotEmpty() && l.length < 30 && visible(n) }
            .minByOrNull { n -> COPY_KEYS.indexOfFirst { it in label(n) }.let { if (it < 0) 99 else it } }
            ?.takeIf { n -> COPY_KEYS.any { it in label(n) } }

    /** Clicks the node (or its clickable parent); falls back to a tap gesture at its center */
    private fun click(n: AccessibilityNodeInfo) {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
        val r = Rect(); n.getBoundsInScreen(r)
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(), null, null)
    }
}
