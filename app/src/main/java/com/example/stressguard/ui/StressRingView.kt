package com.example.stressguard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.use
import androidx.core.graphics.ColorUtils
import com.example.stressguard.R
import kotlin.math.min

/**
 * The stress gauge: an open arc, not a full circle.
 *
 * Replaces the Material [com.google.android.material.progressindicator.CircularProgressIndicator]
 * the dashboard used to carry. A closed ring has no beginning and no end, so a reading of 5% and a
 * reading of 95% look equally "complete" at a glance; an arc with a visible gap reads as a dial,
 * where the empty part of the sweep is as informative as the filled part.
 *
 * The colour is set by the caller from the severity of the prediction, and the gradient is derived
 * from it rather than fixed, so a green arc and a red arc get the same treatment without the class
 * knowing anything about what the colours mean.
 */
class StressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcBounds = RectF()
    private var animator: ValueAnimator? = null

    /** What is drawn, 0..100. Distinct from the requested value while an animation is running. */
    private var drawnProgress = 0f

    /** Stroke width of both arcs. */
    var thickness: Float = resources.getDimension(R.dimen.gauge_thickness)
        set(value) {
            field = value
            trackPaint.strokeWidth = value
            progressPaint.strokeWidth = value
            updateShader()
            invalidate()
        }

    var ringColor: Int = ContextCompat.getColor(context, R.color.stress_low)
        set(value) {
            field = value
            // Also as a flat colour, which is what draws until the view has a size and the
            // gradient can be built.
            progressPaint.color = value
            updateShader()
            invalidate()
        }

    var trackColor: Int = ContextCompat.getColor(context, R.color.hero_track)
        set(value) {
            field = value
            trackPaint.color = value
            invalidate()
        }

    init {
        context.obtainStyledAttributes(attrs, R.styleable.StressRingView).use {
            thickness = it.getDimension(R.styleable.StressRingView_ringThickness, thickness)
            ringColor = it.getColor(R.styleable.StressRingView_ringColor, ringColor)
            trackColor = it.getColor(R.styleable.StressRingView_ringTrackColor, trackColor)
            drawnProgress = it.getInt(R.styleable.StressRingView_ringProgress, 0).toFloat()
        }
        // Repeated after the attribute pass because a property initialiser does not run through
        // its own setter, so a gauge declared with no attributes would otherwise draw hairlines.
        trackPaint.strokeWidth = thickness
        trackPaint.color = trackColor
        progressPaint.strokeWidth = thickness
        progressPaint.color = ringColor
    }

    /**
     * Moves the arc to [value] percent.
     *
     * Animated by default: a gauge that jumps looks like a rendering glitch, and the sweep is also
     * the only cue that a new reading arrived at all when the number happens to land nearby.
     */
    fun setProgress(value: Int, animate: Boolean = true) {
        val target = value.coerceIn(0, 100).toFloat()
        animator?.cancel()

        if (!animate || !isAttachedToWindow) {
            drawnProgress = target
            invalidate()
            return
        }

        animator = ValueAnimator.ofFloat(drawnProgress, target).apply {
            duration = ANIMATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawnProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    /** Square, at the smaller of the two offered dimensions: an oval gauge is not a gauge. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // Half the stroke, or the rounded caps are clipped by the view bounds.
        val inset = thickness / 2f
        arcBounds.set(inset, inset, width - inset, height - inset)
        updateShader()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint)

        val sweep = SWEEP_ANGLE * (drawnProgress / 100f)
        // Below about a degree the round cap alone is wider than the arc, which draws a dot at
        // zero and implies a reading that does not exist.
        if (sweep >= MIN_VISIBLE_SWEEP) {
            canvas.drawArc(arcBounds, START_ANGLE, sweep, false, progressPaint)
        }
    }

    /**
     * A sweep gradient along the arc, from a darkened form of the ring colour to the colour
     * itself, so the dial has depth without a second colour having to be chosen for every
     * severity.
     */
    private fun updateShader() {
        if (width == 0 || height == 0) return

        val centerX = width / 2f
        val centerY = height / 2f
        val shader = SweepGradient(
            centerX,
            centerY,
            intArrayOf(ColorUtils.blendARGB(ringColor, Color.BLACK, 0.3f), ringColor, ringColor),
            floatArrayOf(0f, SWEEP_ANGLE / 360f, 1f),
        )
        // The gradient starts at 3 o'clock; rotate it to start where the arc does.
        shader.setLocalMatrix(Matrix().apply { postRotate(START_ANGLE, centerX, centerY) })
        progressPaint.shader = shader
    }

    private companion object {
        /** Eight o'clock. The gap sits at the bottom, under the figure it is reporting. */
        const val START_ANGLE = 140f
        const val SWEEP_ANGLE = 260f
        const val MIN_VISIBLE_SWEEP = 1f
        const val ANIMATION_MS = 650L
    }
}
