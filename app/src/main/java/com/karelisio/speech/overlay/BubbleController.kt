package com.karelisio.speech.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnticipateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.karelisio.speech.dictation.DictationController.State
import com.karelisio.speech.util.Prefs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Fenêtres overlay de la bulle (TYPE_ACCESSIBILITY_OVERLAY : au-dessus du clavier, sans
 * autorisation supplémentaire) et gestion des gestes : tap, appui long (maintenir pour parler),
 * glisser avec aimantation au bord, glisser vers la zone ✕ pour masquer.
 */
class BubbleController(
    private val context: Context,
    private val prefs: Prefs,
    private val listener: Listener,
) {
    interface Listener {
        fun onTap()
        fun onHoldStart()
        fun onHoldEnd()
        fun onDismiss()
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val bubble = BubbleView(context, dp(prefs.bubbleSizeDp))
    private val target = DismissTargetView(context, dp(72))
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val holdDelay = ViewConfiguration.getLongPressTimeout().toLong()

    private val lp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val targetLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(64)
    }

    /** Visible pour l'utilisateur (une animation de sortie peut encore tourner). */
    var isShown = false
        private set
    /** Vue attachée au WindowManager. */
    private var attached = false
    private var targetShown = false
    private var snapAnim: ValueAnimator? = null

    init {
        bubble.setOnTouchListener(TouchHandler())
    }

    fun show() {
        if (isShown) return
        isShown = true
        bubble.animate().cancel()
        if (!attached) {
            val (w, h) = screenSize()
            val total = bubble.totalPx
            lp.x = if (prefs.bubbleRight) w - total else 0
            lp.y = (prefs.bubbleY * h).roundToInt().coerceIn(0, h - total)
            bubble.alpha = 0f
            bubble.scaleX = 0.2f
            bubble.scaleY = 0.2f
            bubble.rotation = if (prefs.bubbleRight) 25f else -25f
            if (runCatching { wm.addView(bubble, lp) }.isFailure) {
                isShown = false
                return
            }
            attached = true
        }
        // Entrée : « pop » avec léger rebond et petite rotation qui se redresse.
        bubble.animate()
            .alpha(prefs.bubbleOpacity)
            .scaleX(1f).scaleY(1f)
            .rotation(0f)
            .setDuration(340)
            .setInterpolator(OvershootInterpolator(2.2f))
            .withEndAction(null)
            .start()
    }

    fun hide() {
        hideTarget()
        if (!isShown) return
        isShown = false
        snapAnim?.cancel()
        bubble.animate().cancel()
        // Sortie : la bulle se contracte et s'efface, puis la fenêtre est retirée.
        bubble.animate()
            .alpha(0f)
            .scaleX(0.2f).scaleY(0.2f)
            .setDuration(200)
            .setInterpolator(AnticipateInterpolator(1.2f))
            .withEndAction {
                if (!isShown && attached) {
                    runCatching { wm.removeView(bubble) }
                    attached = false
                }
            }
            .start()
    }

    fun setState(state: State) { bubble.state = state }
    fun setLevel(level: Float) { bubble.level = level }
    fun setCleaning(on: Boolean) { bubble.cleaning = on }

    fun applyAppearance(sizeDp: Int, opacity: Float) {
        if (isShown) bubble.alpha = opacity
        if (bubble.diameterPx != dp(sizeDp)) {
            bubble.diameterPx = dp(sizeDp)
            if (attached) runCatching { wm.updateViewLayout(bubble, lp) }
        }
    }

    fun haptic() {
        if (!prefs.haptics) return
        val c = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        bubble.performHapticFeedback(c)
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = context.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun showTarget() {
        if (targetShown) return
        target.animate().cancel()
        target.alpha = 0f
        target.scaleX = 0.5f
        target.scaleY = 0.5f
        target.translationY = dp(24).toFloat()
        if (target.parent == null && runCatching { wm.addView(target, targetLp) }.isFailure) return
        targetShown = true
        target.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(220).setInterpolator(OvershootInterpolator(1.5f)).withEndAction(null).start()
    }

    private fun hideTarget() {
        if (!targetShown) return
        targetShown = false
        target.active = false
        target.animate().cancel()
        target.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).translationY(dp(24).toFloat())
            .setDuration(160).setInterpolator(AccelerateInterpolator())
            .withEndAction { if (!targetShown && target.parent != null) runCatching { wm.removeView(target) } }
            .start()
    }

    private fun overTarget(): Boolean {
        val (w, h) = screenSize()
        val total = bubble.totalPx
        val bx = lp.x + total / 2f
        val by = lp.y + total / 2f
        val tx = w / 2f
        val ty = h - targetLp.y - dp(72) / 2f
        return hypot(bx - tx, by - ty) < dp(72)
    }

    private fun snapToEdge() {
        val (w, h) = screenSize()
        val total = bubble.totalPx
        val right = lp.x + total / 2 > w / 2
        val endX = if (right) w - total else 0
        prefs.bubbleRight = right
        prefs.bubbleY = lp.y.toFloat() / h
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(lp.x, endX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                if (attached) runCatching { wm.updateViewLayout(bubble, lp) }
            }
            start()
        }
    }

    private inner class TouchHandler : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var holding = false
        private val holdRunnable = Runnable {
            holding = true
            haptic()
            listener.onHoldStart()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    snapAnim?.cancel()
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false; holding = false
                    v.postDelayed(holdRunnable, holdDelay)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !holding && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(holdRunnable)
                        showTarget()
                    }
                    if (dragging) {
                        lp.x = startX + dx.roundToInt()
                        lp.y = startY + dy.roundToInt()
                        runCatching { wm.updateViewLayout(bubble, lp) }
                        target.active = overTarget()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(holdRunnable)
                    when {
                        holding -> { haptic(); listener.onHoldEnd() }
                        dragging -> {
                            val dismiss = overTarget()
                            hideTarget()
                            if (dismiss) { haptic(); listener.onDismiss() } else snapToEdge()
                        }
                        e.actionMasked == MotionEvent.ACTION_UP -> { haptic(); listener.onTap() }
                    }
                    dragging = false; holding = false
                }
            }
            return true
        }
    }
}
