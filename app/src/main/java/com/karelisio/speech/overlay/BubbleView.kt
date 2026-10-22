package com.karelisio.speech.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.karelisio.speech.R
import com.karelisio.speech.dictation.DictationController.State

/** Bulle de dictée : disque + micro, anneau qui suit le niveau sonore, arc qui tourne pendant la transcription. */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, diameterPx: Int) : View(context) {
    var diameterPx: Int = diameterPx
        set(v) { field = v; requestLayout(); invalidate() }

    var state: State = State.IDLE
        set(v) {
            if (field == v) return
            field = v
            if (v == State.PROCESSING) spinner.start() else spinner.cancel()
            invalidate()
        }

    var level: Float = 0f
        set(v) {
            field = v
            if (state == State.RECORDING) invalidate()
        }

    /** Petite étincelle en haut à droite quand le nettoyage IA est actif. */
    var cleaning: Boolean = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val sparkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val sparkPath = android.graphics.Path()

    private val accent = if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_500) else Color.rgb(81, 68, 211)
    private val accent2 = if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent3_500) else Color.rgb(0, 137, 127)
    private val recording = Color.rgb(229, 72, 77)
    private val recording2 = Color.rgb(255, 138, 76)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        maskFilter = BlurMaskFilter(1f, BlurMaskFilter.Blur.NORMAL)
    }
    // Dégradés et flou recalculés seulement quand la taille change (pas d'allocation dans onDraw).
    private var idleShader: Shader? = null
    private var recShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val r = diameterPx / 2f
        idleShader = LinearGradient(cx - r, cy - r, cx + r, cy + r, accent, accent2, Shader.TileMode.CLAMP)
        recShader = LinearGradient(cx - r, cy - r, cx + r, cy + r, recording, recording2, Shader.TileMode.CLAMP)
        shadow.maskFilter = BlurMaskFilter(r * 0.18f, BlurMaskFilter.Blur.NORMAL)
    }

    // Les gestes sont gérés par BubbleController ; performClick garde l'accessibilité correcte.
    override fun performClick(): Boolean = super.performClick()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val icon = ContextCompat.getDrawable(context, R.drawable.ic_notification)!!.mutate()
    private val oval = RectF()
    private var angle = 0f
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { angle = it.animatedValue as Float; invalidate() }
    }

    /** Marge autour du disque pour l'anneau de niveau sonore. */
    val totalPx: Int get() = (diameterPx * 1.5f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(totalPx, totalPx)

    init {
        // Ombre floue : BlurMaskFilter n'est pas accéléré matériellement.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = diameterPx / 2f
        val rec = state == State.RECORDING
        val c1 = if (rec) recording else accent
        val c2 = if (rec) recording2 else accent2
        if (rec) {
            ring.color = c1
            ring.alpha = 80
            canvas.drawCircle(cx, cy, r + level * (width / 2f - r), ring)
        } else {
            canvas.drawCircle(cx, cy + r * 0.08f, r * 0.98f, shadow)
        }
        fill.shader = if (rec) recShader else idleShader
        canvas.drawCircle(cx, cy, r, fill)
        if (state == State.PROCESSING) {
            arc.strokeWidth = r * 0.14f
            val a = r * 0.62f
            oval.set(cx - a, cy - a, cx + a, cy + a)
            canvas.drawArc(oval, angle, 100f, false, arc)
        } else {
            val s = (r * 0.55f).toInt()
            icon.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            icon.draw(canvas)
        }
        if (cleaning) drawSpark(canvas, cx + r * 0.72f, cy - r * 0.72f, r * 0.34f, c2)
    }

    /** Étoile à quatre branches sur une pastille blanche. */
    private fun drawSpark(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        canvas.drawCircle(x, y, size, sparkFill)
        val k = size * 0.62f
        val w = k * 0.28f
        sparkPath.reset()
        sparkPath.moveTo(x, y - k)
        sparkPath.quadTo(x + w, y - w, x + k, y)
        sparkPath.quadTo(x + w, y + w, x, y + k)
        sparkPath.quadTo(x - w, y + w, x - k, y)
        sparkPath.quadTo(x - w, y - w, x, y - k)
        sparkPath.close()
        ring.color = color
        ring.alpha = 255
        canvas.drawPath(sparkPath, ring)
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }
}

/** Zone « ✕ » affichée en bas de l'écran pendant un glissement de la bulle. */
@SuppressLint("ViewConstructor")
class DismissTargetView(context: Context, private val sizePx: Int) : View(context) {
    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(sizePx, sizePx)

    override fun onDraw(canvas: Canvas) {
        val c = sizePx / 2f
        val r = if (active) c else c * 0.8f
        bg.color = if (active) Color.argb(230, 229, 57, 53) else Color.argb(170, 40, 40, 40)
        canvas.drawCircle(c, c, r, bg)
        cross.strokeWidth = sizePx * 0.06f
        val d = r * 0.35f
        canvas.drawLine(c - d, c - d, c + d, c + d, cross)
        canvas.drawLine(c - d, c + d, c + d, c - d, cross)
    }
}
