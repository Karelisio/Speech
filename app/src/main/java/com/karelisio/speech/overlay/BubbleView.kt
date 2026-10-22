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

    private val accent = if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_500) else Color.rgb(81, 68, 211)
    private val accent2 = if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent3_500) else Color.rgb(0, 137, 127)
    private val recording = Color.rgb(229, 72, 77)
    private val recording2 = Color.rgb(255, 138, 76)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 0, 0)
        maskFilter = BlurMaskFilter(1f, BlurMaskFilter.Blur.NORMAL)
    }
    private var shaderKey = 0
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
        val (c1, c2) = if (rec) recording to recording2 else accent to accent2
        if (rec) {
            ring.color = c1
            ring.alpha = 80
            canvas.drawCircle(cx, cy, r + level * (width / 2f - r), ring)
        } else {
            shadow.maskFilter = BlurMaskFilter(r * 0.18f, BlurMaskFilter.Blur.NORMAL)
            canvas.drawCircle(cx, cy + r * 0.08f, r * 0.98f, shadow)
        }
        val key = (if (rec) 1 else 0) * 31 + width
        if (key != shaderKey) {
            fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, c1, c2, Shader.TileMode.CLAMP)
            shaderKey = key
        }
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
