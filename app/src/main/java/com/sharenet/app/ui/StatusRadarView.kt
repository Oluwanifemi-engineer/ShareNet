package com.sharenet.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.sharenet.app.R

/**
 * The status "orb": a calm signal mark for the hero card.
 *
 * - Idle:      three static rings, muted.
 * - Starting:  one soft pulse ring breathing slowly.
 * - Active:    two pulse rings expanding continuously + a bright core.
 * - Error:     rings turn red, no motion.
 *
 * Pure View drawing — no image assets, adapts to the theme colors.
 */
class StatusRadarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { IDLE, STARTING, ACTIVE, ERROR }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            refreshPaint()
            if (value == Mode.ACTIVE || value == Mode.STARTING) startAnimator() else stopAnimator()
            invalidate()
        }

    private var coreColor = ContextCompat.getColor(context, R.color.md_primary)
    private var ringColor = ContextCompat.getColor(context, R.color.md_primary)

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = coreColor
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ringColor
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = ringColor
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
    }
    private var progress = 0f

    private val slowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1800L
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            slow = it.animatedValue as Float
            invalidate()
        }
    }
    private var slow = 0f

    init {
        refreshPaint()
    }

    private fun refreshPaint() {
        val colorRes = when (mode) {
            Mode.ACTIVE, Mode.STARTING -> R.color.md_primary
            Mode.ERROR -> R.color.md_error
            Mode.IDLE -> R.color.md_outline
        }
        ringColor = ContextCompat.getColor(context, colorRes)
        coreColor = if (mode == Mode.IDLE) {
            ContextCompat.getColor(context, R.color.md_outline)
        } else {
            ringColor
        }
        ringPaint.color = ringColor
        pulsePaint.color = ringColor
        corePaint.color = coreColor
    }

    private fun startAnimator() {
        if (!animator.isStarted) animator.start()
        if (mode == Mode.STARTING && !slowAnimator.isStarted) slowAnimator.start()
    }

    private fun stopAnimator() {
        animator.cancel()
        slowAnimator.cancel()
        progress = 0f
        slow = 0f
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
        slowAnimator.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f

        when (mode) {
            Mode.IDLE -> {
                for (i in 0 until 3) {
                    val r = base * (0.30f + i * 0.20f)
                    ringPaint.alpha = 90 - i * 20
                    canvas.drawCircle(cx, cy, r, ringPaint)
                }
                canvas.drawCircle(cx, cy, base * 0.16f, corePaint)
            }

            Mode.STARTING -> {
                val breathe = 0.72f + 0.16f * slow
                ringPaint.alpha = 130
                canvas.drawCircle(cx, cy, base * 0.40f * breathe, ringPaint)
                ringPaint.alpha = 70
                canvas.drawCircle(cx, cy, base * 0.60f * breathe, ringPaint)
                canvas.drawCircle(cx, cy, base * 0.15f, corePaint)
            }

            Mode.ACTIVE -> {
                val p1 = ((progress * 1.35f) % 1f)
                val p2 = ((progress * 1.35f + 0.5f) % 1f)
                drawPulse(canvas, cx, cy, base, p1, 200)
                drawPulse(canvas, cx, cy, base, p2, 150)
                canvas.drawCircle(cx, cy, base * 0.16f, corePaint)
                ringPaint.alpha = 90
                canvas.drawCircle(cx, cy, base * 0.30f, ringPaint)
            }

            Mode.ERROR -> {
                for (i in 0 until 3) {
                    val r = base * (0.30f + i * 0.20f)
                    ringPaint.alpha = 110 - i * 25
                    canvas.drawCircle(cx, cy, r, ringPaint)
                }
                canvas.drawCircle(cx, cy, base * 0.16f, corePaint)
            }
        }
    }

    private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, base: Float, p: Float, alpha: Int) {
        val eased = (1f - (1f - p) * (1f - p))
        val r = base * (0.18f + 0.62f * eased)
        pulsePaint.alpha = (alpha * (1f - p)).toInt()
        canvas.drawCircle(cx, cy, r, pulsePaint)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    override fun setBackgroundColor(color: Int) = Unit // keep the view clean
}
