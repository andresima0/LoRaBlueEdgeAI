package com.android.lorablueedge.chart

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Minimal line chart drawn directly with Canvas — no external charting
 * library. Plots (timestamp, value) points left-to-right by time, with a
 * value axis on the left and up to 4 time labels along the bottom.
 *
 * Deliberately simple: no zoom/pan, no animation, no legend. This is
 * enough for a single-series 10-minute telemetry window; if richer
 * interaction is needed later, swapping in a real charting library
 * remains an option once the Kotlin/KSP plugin classpath issue is
 * resolved at the project level.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var points: List<Pair<Long, Double>> = emptyList()

    /**
     * Optional fixed Y-axis range. When non-null, the chart always spans
     * exactly [first, second] regardless of the data values — useful for
     * water level where 0 m (empty) and the configured total depth (full)
     * are the natural physical bounds.
     *
     * When null the range is auto-calculated from the data (existing
     * behaviour, used for every other metric).
     */
    private var yRange: Pair<Double, Double>? = null

    fun setYRange(range: Pair<Double, Double>?) {
        yRange = range
        invalidate()
    }

    private val linePaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val pointPaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val axisPaint = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#EEEEEE")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }

    private val textPaint = Paint().apply {
        color = Color.parseColor("#888888")
        textSize = 28f
        isAntiAlias = true
    }

    // Smaller paint used for per-point value labels so they don't collide
    // with each other when points are close together horizontally.
    private val pointLabelPaint = Paint().apply {
        color = Color.parseColor("#3F51B5")
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val emptyTextPaint = Paint().apply {
        color = Color.parseColor("#AAAAAA")
        textSize = 32f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val paddingLeft   = 90f
    private val paddingBottom = 60f
    private val paddingTop    = 30f
    private val paddingRight  = 20f

    fun setPoints(newPoints: List<Pair<Long, Double>>) {
        points = newPoints
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (points.size < 2) {
            canvas.drawText(
                if (points.isEmpty()) "No data yet" else "Waiting for more data...",
                width / 2f,
                height / 2f,
                emptyTextPaint
            )
            return
        }

        val chartLeft   = paddingLeft
        val chartRight  = width - paddingRight
        val chartTop    = paddingTop
        val chartBottom = height - paddingBottom

        val minTime   = points.first().first
        val maxTime   = points.last().first
        val timeRange = (maxTime - minTime).coerceAtLeast(1L)

        // Use the fixed physical range when provided (e.g. 0..totalDepth for
        // water level), otherwise auto-calculate from the data and add a small
        // padding so the line never sits exactly on an axis edge.
        val (displayMin, displayMax) = yRange?.let { (lo, hi) ->
            lo to hi
        } ?: run {
            val minValue   = points.minOf { it.second }
            val maxValue   = points.maxOf { it.second }
            val valueRange = (maxValue - minValue).let { if (it < 0.0001) 1.0 else it }
            val pad        = valueRange * 0.1
            (minValue - pad) to (maxValue + pad)
        }
        val displayRange = (displayMax - displayMin).coerceAtLeast(0.0001)

        fun xFor(timestamp: Long): Float =
            chartLeft + ((timestamp - minTime).toFloat() / timeRange) * (chartRight - chartLeft)

        fun yFor(value: Double): Float =
            chartBottom - ((value - displayMin) / displayRange).toFloat() * (chartBottom - chartTop)

        // ── Y-axis grid + labels ─────────────────────────────────────────────
        // Compute ~5 evenly spaced "nice" tick values so the grid lines and
        // labels divide the axis into readable intervals.
        val targetTicks = 5
        val rawStep     = (displayMax - displayMin) / targetTicks
        // Round the step to a "nice" number (nearest 0.05 for water level).
        val niceStep    = niceStep(rawStep)
        val firstTick   = Math.ceil(displayMin / niceStep) * niceStep

        var tick = firstTick
        while (tick <= displayMax + niceStep * 0.01) {
            val y = yFor(tick)
            // Horizontal dashed grid line
            canvas.drawLine(chartLeft, y, chartRight, y, gridPaint)
            // Y-axis tick label with 2 decimal places for precision
            canvas.drawText(String.format("%.2f", tick), 4f, y + 10f, textPaint)
            tick += niceStep
        }

        // ── Axes ─────────────────────────────────────────────────────────────
        canvas.drawLine(chartLeft, chartTop,    chartLeft,  chartBottom, axisPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, axisPaint)

        // ── X-axis labels: first, middle, last timestamp ─────────────────────
        val labelIndices = listOf(0, points.size / 2, points.size - 1).distinct()
        labelIndices.forEach { i ->
            val (timestamp, _) = points[i]
            val label = timeFormatter.format(timestamp)
            val x     = xFor(timestamp)
            canvas.drawText(label, (x - 60f).coerceAtLeast(chartLeft), height - 15f, textPaint)
        }

        // ── Line connecting every point ───────────────────────────────────────
        for (i in 0 until points.size - 1) {
            val (t1, v1) = points[i]
            val (t2, v2) = points[i + 1]
            canvas.drawLine(xFor(t1), yFor(v1), xFor(t2), yFor(v2), linePaint)
        }

        // ── Dots + per-point value labels ─────────────────────────────────────
        // Labels are suppressed when there are too many points to avoid clutter.
        // A minimum horizontal gap is enforced so labels don't overlap when
        // consecutive points are packed tightly (e.g. many readings per minute).
        // The LAST point always gets a label so the current value is always visible.
        // Intermediate labels appear whenever the horizontal gap since the previous
        // label is wide enough to avoid overlap. No hard cap on number of points —
        // spacing alone governs how many labels fit.
        val showDots      = points.size <= 60
        val minLabelGapPx = pointLabelPaint.textSize * 7f  // room for "0.25 (92%)"
        var lastLabelX    = Float.NEGATIVE_INFINITY
        val lastIndex     = points.size - 1

        points.forEachIndexed { index, (t, v) ->
            val x = xFor(t)
            val y = yFor(v)

            if (showDots) canvas.drawCircle(x, y, 6f, pointPaint)

            val isLast = index == lastIndex
            val gapOk  = x - lastLabelX >= minLabelGapPx
            if (isLast || gapOk) {
                // When a fixed Y range is set (water level with configured depth),
                // append the fill percentage next to the value: "0.23 (92%)"
                val label = buildPointLabel(v)
                // Place label above the dot; clamp so it never clips into paddingTop.
                val labelY = (y - 14f).coerceAtLeast(chartTop + pointLabelPaint.textSize)
                canvas.drawText(label, x, labelY, pointLabelPaint)
                lastLabelX = x
            }
        }
    }

    /**
     * Builds the label string for a data point. When a fixed Y range is
     * available (yRange != null, meaning totalDepth is configured), appends
     * the fill percentage so the label reads e.g. "0.23 (92%)". Without a
     * range it falls back to just the value "0.23".
     */
    private fun buildPointLabel(value: Double): String {
        val totalDepth = yRange?.second
        return if (totalDepth != null && totalDepth > 0.0) {
            val pct = (value / totalDepth * 100.0).coerceIn(0.0, 100.0).toInt()
            String.format("%.2f (%d%%)", value, pct)
        } else {
            String.format("%.2f", value)
        }
    }

    /**
     * Rounds [step] to a "nice" interval suitable for axis ticks.
     * Tries multiples of 1, 2, 2.5, and 5 at the appropriate power of 10.
     */
    private fun niceStep(step: Double): Double {
        if (step <= 0) return 1.0
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(step)))
        val normalized = step / magnitude
        val nice = when {
            normalized <= 1.0  -> 1.0
            normalized <= 2.0  -> 2.0
            normalized <= 2.5  -> 2.5
            normalized <= 5.0  -> 5.0
            else               -> 10.0
        }
        return nice * magnitude
    }
}