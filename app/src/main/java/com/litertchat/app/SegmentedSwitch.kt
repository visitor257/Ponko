package com.litertchat.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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

    /**
     * 待回调的档位。吸附动画结束后才通知外部——外部（换主题）会重建整页视图，
     * 如果在动画一开始就回调，滑块动画会被当场掐断，看起来像掉帧。
     */
    private var pendingNotify: Int? = null

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
                // 上一段吸附还没播完就又开始拖：先把它的回调补发掉，别把那次选择吞了
                flushNotify()
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
        if (target != index) {
            index = target
            pendingNotify = target
        }
        anim = ValueAnimator.ofFloat(pos, target.toFloat()).apply {
            duration = 140L
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            // 动画跑完再回调：让滑块先稳稳吸附到位，外部再去做重建视图之类的重活
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) = flushNotify()
            })
            start()
        }
    }

    /** 补发挂起的档位回调（动画结束 / 下一次触摸 / 静默设值前都会调用） */
    private fun flushNotify() {
        val t = pendingNotify ?: return
        pendingNotify = null
        onChanged(t)
    }

    /** 外部直接设定档位（不触发回调） */
    fun setIndexSilently(i: Int) {
        if (i !in labels.indices) return
        pendingNotify = null
        anim?.cancel()
        index = i
        pos = i.toFloat()
        invalidate()
    }

    fun currentIndex(): Int = index
}
