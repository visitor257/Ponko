package com.litertchat.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * 多档滑块开关（拖拽 / 点按都行）。
 *
 * 轨道上均分 [labels].size 个档位，滑块松手后吸附到最近一档，并回调 onChanged。
 * 外观（轨道 / 滑块 / 文字色）全部取自 [PonkoTheme]，深浅色自动跟随。
 */
class SegmentedSwitch @JvmOverloads constructor(
    context: Context,
    private val labels: Array<String> = arrayOf("", ""),
    private val pal: PonkoTheme = PonkoTheme.light(),
    private var index: Int = 0,
    attr: AttributeSet? = null,
    /** 档位变化回调（构造后可赋值）；内部换档时才触发 */
    var onChanged: (Int) -> Unit = {},
) : View(context, attr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    private val density = resources.displayMetrics.density
    private fun dpV(v: Float) = v * density

    private val trackHeight = dpV(42f)
    private val innerPad = dpV(3.5f)
    private val textSize = dpV(12.5f)

    /** 滑块当前连续位置（0 .. n-1） */
    private var pos = index.toFloat()
    private var downRawX = 0f
    private var downPos = 0f
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        contentDescription = labels.joinToString(" / ")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (trackHeight + dpV(2f)).toInt())
    }

    private fun stepWidth(): Float = (width - innerPad * 2f) / labels.size.toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = trackHeight
        val r = h / 2f
        val n = labels.size

        // 轨道
        trackPaint.color = pal.switchTrack
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        // 滑块
        val sw = stepWidth()
        val thumbL = innerPad + pos * sw
        thumbPaint.color = pal.switchThumb
        rect.set(thumbL + innerPad, innerPad, thumbL + sw - innerPad, h - innerPad)
        val tr = (h - innerPad * 2f) / 2f
        canvas.drawRoundRect(rect, tr, tr, thumbPaint)
        strokePaint.color = pal.border
        strokePaint.strokeWidth = dpV(1f)
        canvas.drawRoundRect(rect, tr, tr, strokePaint)

        // 档位文字：跟着滑块最近的档高亮
        val near = pos.roundToInt().coerceIn(0, n - 1)
        textPaint.textSize = textSize
        val baseline = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        for (i in 0 until n) {
            val selected = i == near
            textPaint.color = if (selected) pal.primary else pal.subText
            textPaint.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            val cx = innerPad + i * sw + sw / 2f
            canvas.drawText(labels[i], cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (labels.size < 2) return false
        val sw = stepWidth()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                anim?.cancel()
                downRawX = event.rawX
                pos = ((event.x - innerPad) / sw - 0.5f).coerceIn(0f, (labels.size - 1).toFloat())
                downPos = pos
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                pos = (downPos + (event.rawX - downRawX) / sw).coerceIn(0f, (labels.size - 1).toFloat())
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                snapTo(pos.roundToInt().coerceIn(0, labels.size - 1))
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun snapTo(target: Int) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(pos, target.toFloat()).apply {
            duration = 140L
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        if (target != index) {
            index = target
            onChanged(target)
        }
    }

    /** 外部直接设定档位（不触发回调） */
    fun setIndexSilently(i: Int) {
        if (i !in labels.indices) return
        index = i
        pos = i.toFloat()
        invalidate()
    }

    fun currentIndex(): Int = index
}
