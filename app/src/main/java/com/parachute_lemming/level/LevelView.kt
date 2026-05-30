package com.parachute_lemming.level

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.min

/**
 * Renders either a round bubble level (flat-on-back) or a torpedo level (on long edge).
 * Bubble offsets are unit-scaled in [-1, 1]; the caller maps tilt → offset.
 *
 * Alignment flags ([rollCentered], [pitchCentered], [tiltCentered]) are computed by
 * MainActivity from true angles vs. user-configurable tolerances, then handed in here.
 */
class LevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { ROUND, TORPEDO }

    var mode: Mode = Mode.ROUND
        set(value) { if (field != value) { field = value; invalidate() } }

    var bubbleX: Float = 0f
    var bubbleY: Float = 0f

    /** True when |roll| ≤ roll tolerance — the vertical guide line glows. */
    var rollCentered: Boolean = false
    /** True when |pitch| ≤ pitch tolerance — the horizontal guide line glows. */
    var pitchCentered: Boolean = false
    /** True when |tilt| ≤ pitch tolerance in torpedo mode — center line glows. */
    var tiltCentered: Boolean = false

    private val bezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.instrument_bezel)
        style = Paint.Style.FILL
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.instrument_face)
        style = Paint.Style.FILL
    }
    private val faceOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val glassEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_edge)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val bgCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crosshair)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    // Same dashed line as the background crosshair, but amber + slightly thicker — used
    // to "light up" an individual axis when its angle is within tolerance.
    private val bgCrosshairGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crosshair_centered)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
    }
    private val matchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.match_mark)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val targetRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.target_ring)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.bubble)
        style = Paint.Style.FILL
        alpha = BUBBLE_ALPHA
    }
    private val bubbleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.bubble_edge)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val bubbleMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.bubble_mark)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
    }
    private val magnifyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.crosshair)
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(10f)), 0f)
        strokeCap = Paint.Cap.ROUND
        alpha = 90
    }

    private val colorBubble = ContextCompat.getColor(context, R.color.bubble)
    private val colorBubbleCentered = ContextCompat.getColor(context, R.color.bubble_centered)
    private val colorFace = ContextCompat.getColor(context, R.color.instrument_face)
    private val colorFaceCentered = ContextCompat.getColor(context, R.color.instrument_face_centered)
    private val colorBezel = ContextCompat.getColor(context, R.color.instrument_bezel)
    private val colorBezelAligned = ContextCompat.getColor(context, R.color.instrument_bezel_aligned)
    private val colorMatchAligned = ContextCompat.getColor(context, R.color.match_mark_aligned)
    private val colorTargetRing = ContextCompat.getColor(context, R.color.target_ring)

    private var roundFaceShader: Shader? = null
    private var torpedoFaceShaderVertical: Shader? = null
    private var torpedoFaceShaderHorizontal: Shader? = null
    private val bubbleClipPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // During the configChanges rotation transition the view can briefly be laid out at
        // a degenerate size. Build each shader only when it has a positive extent — a
        // RadialGradient with radius <= 0 throws and crashes the layout pass.
        if (w <= 0 || h <= 0) {
            roundFaceShader = null
            torpedoFaceShaderVertical = null
            torpedoFaceShaderHorizontal = null
            return
        }

        val cx = w / 2f
        val cy = h / 2f
        val outerR = min(w, h) / 2f - dp(8f)
        val faceR = outerR - dp(10f)

        roundFaceShader = if (faceR > 0f) RadialGradient(
            cx - faceR * 0.2f, cy - faceR * 0.25f, faceR * 1.1f,
            intArrayOf(0x40FFFFFF.toInt(), 0x00000000, 0x30000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        ) else null

        // Vertical-torpedo cylinder shading: dark at left/right edges, lighter center.
        val capWV = (w * 0.36f).coerceAtMost(dp(160f))
        torpedoFaceShaderVertical = if (capWV > 0f) {
            val leftV = (w - capWV) / 2f
            val rightV = leftV + capWV
            LinearGradient(
                leftV, 0f, rightV, 0f,
                intArrayOf(0x40000000.toInt(), 0x30FFFFFF.toInt(), 0x40000000.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else null

        // Horizontal-torpedo cylinder shading: dark at top/bottom edges, lighter center.
        val capHH = (h * 0.55f).coerceAtMost(dp(160f))
        torpedoFaceShaderHorizontal = if (capHH > 0f) {
            val topH = (h - capHH) / 2f
            val bottomH = topH + capHH
            LinearGradient(
                0f, topH, 0f, bottomH,
                intArrayOf(0x40000000.toInt(), 0x30FFFFFF.toInt(), 0x40000000.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        } else null
    }

    override fun onDraw(canvas: Canvas) {
        when (mode) {
            Mode.ROUND -> drawRound(canvas)
            Mode.TORPEDO -> drawTorpedo(canvas)
        }
    }

    private fun drawRound(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outerR = min(width, height) / 2f - dp(8f)
        val faceR = outerR - dp(10f)
        val targetR = faceR * 0.22f
        val bubbleR = faceR * 0.20f

        val bothCentered = rollCentered && pitchCentered
        bezelPaint.color = if (bothCentered) colorBezelAligned else colorBezel
        facePaint.color = if (bothCentered) colorFaceCentered else colorFace
        bubblePaint.color = if (bothCentered) colorBubbleCentered else colorBubble
        bubblePaint.alpha = BUBBLE_ALPHA
        targetRingPaint.color = if (bothCentered) colorMatchAligned else colorTargetRing
        targetRingPaint.strokeWidth = if (bothCentered) dp(2.5f) else dp(1.5f)

        canvas.drawCircle(cx, cy, outerR, bezelPaint)
        canvas.drawCircle(cx, cy, faceR, facePaint)
        canvas.drawCircle(cx, cy, faceR, glassEdgePaint)
        roundFaceShader?.let {
            faceOverlayPaint.shader = it
            canvas.drawCircle(cx, cy, faceR, faceOverlayPaint)
        }

        val maxOffset = faceR - bubbleR - dp(4f)
        val mag = hypot(bubbleX.toDouble(), bubbleY.toDouble()).toFloat()
        val s = if (mag > 1f) 1f / mag else 1f
        val bx = cx + bubbleX * s * maxOffset
        val by = cy + bubbleY * s * maxOffset
        bubbleClipPath.reset()
        bubbleClipPath.addCircle(bx, by, bubbleR, Path.Direction.CW)

        // Background guide lines — each axis glows independently. Drawn everywhere
        // except inside the bubble; only the magnified version shows there.
        val horizontalPaint = if (pitchCentered) bgCrosshairGlowPaint else bgCrosshairPaint
        val verticalPaint = if (rollCentered) bgCrosshairGlowPaint else bgCrosshairPaint
        canvas.save()
        canvas.clipOutPath(bubbleClipPath)
        canvas.drawLine(cx - faceR, cy, cx + faceR, cy, horizontalPaint)
        canvas.drawLine(cx, cy - faceR, cx, cy + faceR, verticalPaint)
        canvas.restore()

        // Target ring — only glows when both axes are aligned.
        canvas.drawCircle(cx, cy, targetR, targetRingPaint)

        canvas.drawCircle(bx, by, bubbleR, bubblePaint)

        // Magnified guide lines clipped to the bubble.
        canvas.save()
        canvas.clipPath(bubbleClipPath)
        canvas.drawLine(cx - faceR, cy, cx + faceR, cy, magnifyPaint)
        canvas.drawLine(cx, cy - faceR, cx, cy + faceR, magnifyPaint)
        canvas.restore()

        // Bubble's own crosshair.
        canvas.drawLine(bx - bubbleR, by, bx + bubbleR, by, bubbleMarkPaint)
        canvas.drawLine(bx, by - bubbleR, bx, by + bubbleR, bubbleMarkPaint)

        canvas.drawCircle(bx, by, bubbleR, bubbleEdgePaint)
    }

    private fun drawTorpedo(canvas: Canvas) {
        if (width > height) drawTorpedoHorizontal(canvas) else drawTorpedoVertical(canvas)
    }

    private fun drawTorpedoVertical(canvas: Canvas) {
        val pad = dp(8f)
        val capH = height - pad * 2f
        val capW = (width * 0.36f).coerceAtMost(dp(160f))
        val left = (width - capW) / 2f
        val top = pad
        val right = left + capW
        val bottom = top + capH
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val outerRadius = dp(14f)
        val faceInset = dp(6f)
        val faceRadius = (outerRadius - faceInset).coerceAtLeast(dp(2f))

        val bodyRect = RectF(left, top, right, bottom)
        val face = RectF(left + faceInset, top + faceInset, right - faceInset, bottom - faceInset)

        bezelPaint.color = if (tiltCentered) colorBezelAligned else colorBezel
        facePaint.color = if (tiltCentered) colorFaceCentered else colorFace
        bubblePaint.color = if (tiltCentered) colorBubbleCentered else colorBubble
        bubblePaint.alpha = BUBBLE_ALPHA
        matchPaint.strokeWidth = dp(1.5f)

        canvas.drawRoundRect(bodyRect, outerRadius, outerRadius, bezelPaint)
        canvas.drawRoundRect(face, faceRadius, faceRadius, facePaint)
        canvas.drawRoundRect(face, faceRadius, faceRadius, glassEdgePaint)
        torpedoFaceShaderVertical?.let {
            faceOverlayPaint.shader = it
            canvas.drawRoundRect(face, faceRadius, faceRadius, faceOverlayPaint)
        }

        val tickInset = faceInset + dp(4f)
        val tickHalfGap = capW * 0.18f
        canvas.drawLine(left + tickInset, cy - tickHalfGap, right - tickInset, cy - tickHalfGap, matchPaint)
        canvas.drawLine(left + tickInset, cy + tickHalfGap, right - tickInset, cy + tickHalfGap, matchPaint)

        val innerW = capW - faceInset * 2f
        val bubbleR = innerW * 0.30f
        val maxOffset = (capH / 2f) - faceInset - bubbleR - dp(6f)
        val by = cy + bubbleY.coerceIn(-1f, 1f) * maxOffset
        bubbleClipPath.reset()
        bubbleClipPath.addCircle(cx, by, bubbleR, Path.Direction.CW)

        val centerPaint = if (tiltCentered) bgCrosshairGlowPaint else bgCrosshairPaint
        canvas.save()
        canvas.clipOutPath(bubbleClipPath)
        canvas.drawLine(left + tickInset, cy, right - tickInset, cy, centerPaint)
        canvas.restore()

        canvas.drawCircle(cx, by, bubbleR, bubblePaint)

        canvas.save()
        canvas.clipPath(bubbleClipPath)
        canvas.drawLine(left + tickInset, cy, right - tickInset, cy, magnifyPaint)
        canvas.restore()

        canvas.drawLine(cx - bubbleR, by, cx + bubbleR, by, bubbleMarkPaint)
        canvas.drawCircle(cx, by, bubbleR, bubbleEdgePaint)
    }

    private fun drawTorpedoHorizontal(canvas: Canvas) {
        val pad = dp(8f)
        val capH = (height - pad * 2f).coerceAtLeast(0f)
        val capW = (width - pad * 2f).coerceAtLeast(0f)
        if (capH <= 0f || capW <= 0f) return
        val left = pad
        val top = pad
        val right = left + capW
        val bottom = top + capH
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val outerRadius = dp(14f)
        val faceInset = dp(6f)
        val faceRadius = (outerRadius - faceInset).coerceAtLeast(dp(2f))

        val bodyRect = RectF(left, top, right, bottom)
        val face = RectF(left + faceInset, top + faceInset, right - faceInset, bottom - faceInset)

        bezelPaint.color = if (tiltCentered) colorBezelAligned else colorBezel
        facePaint.color = if (tiltCentered) colorFaceCentered else colorFace
        bubblePaint.color = if (tiltCentered) colorBubbleCentered else colorBubble
        bubblePaint.alpha = BUBBLE_ALPHA
        matchPaint.strokeWidth = dp(1.5f)

        canvas.drawRoundRect(bodyRect, outerRadius, outerRadius, bezelPaint)
        canvas.drawRoundRect(face, faceRadius, faceRadius, facePaint)
        canvas.drawRoundRect(face, faceRadius, faceRadius, glassEdgePaint)
        torpedoFaceShaderHorizontal?.let {
            faceOverlayPaint.shader = it
            canvas.drawRoundRect(face, faceRadius, faceRadius, faceOverlayPaint)
        }

        // Vertical match marks crossing the tube — analog of the portrait horizontal ticks.
        val tickInset = faceInset + dp(4f)
        val tickHalfGap = capH * 0.18f
        canvas.drawLine(cx - tickHalfGap, top + tickInset, cx - tickHalfGap, bottom - tickInset, matchPaint)
        canvas.drawLine(cx + tickHalfGap, top + tickInset, cx + tickHalfGap, bottom - tickInset, matchPaint)

        val innerH = capH - faceInset * 2f
        // Bubble roughly fills the tube cross-section like a real torpedo bubble.
        val bubbleR = innerH * 0.42f
        val maxOffset = (capW / 2f) - faceInset - bubbleR - dp(6f)
        val bx = cx + bubbleX.coerceIn(-1f, 1f) * maxOffset
        bubbleClipPath.reset()
        bubbleClipPath.addCircle(bx, cy, bubbleR, Path.Direction.CW)

        // Sight-glass center line — vertical, perpendicular to the tube's length, sitting
        // between the two match marks. Matches the portrait analog (horizontal line at cy).
        val centerPaint = if (tiltCentered) bgCrosshairGlowPaint else bgCrosshairPaint
        canvas.save()
        canvas.clipOutPath(bubbleClipPath)
        canvas.drawLine(cx, top + tickInset, cx, bottom - tickInset, centerPaint)
        canvas.restore()

        canvas.drawCircle(bx, cy, bubbleR, bubblePaint)

        canvas.save()
        canvas.clipPath(bubbleClipPath)
        canvas.drawLine(cx, top + tickInset, cx, bottom - tickInset, magnifyPaint)
        canvas.restore()

        // Vertical mark on the bubble, perpendicular to the tube's length (parallel to
        // the sight-glass center line so they align when the bubble is at zero).
        canvas.drawLine(bx, cy - bubbleR, bx, cy + bubbleR, bubbleMarkPaint)
        canvas.drawCircle(bx, cy, bubbleR, bubbleEdgePaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val BUBBLE_ALPHA = 77 // ~30% opaque
    }
}
