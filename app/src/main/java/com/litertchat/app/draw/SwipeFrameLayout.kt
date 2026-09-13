package com.litertchat.app.draw

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * 只负责「左右滑动切换」的容器：容纳两个子页面，手指横划一下就在两者间切换。
 *
 * 为什么不用 ViewPager2：本页整体被外层 ScrollView 包着，而 ViewPager2 要求子项高度
 * 必须为 match_parent、不支持 wrap_content，套进来会和纵向滚动打架。这里只需要
 * 「滑一下切一页」，一个轻量手势容器就够了，同时保证纵向滚动不被误判。
 */
class SwipeFrameLayout(context: Context) : FrameLayout(context) {

    /** 左滑（手指向左划）时回调 —— 切到下一页 */
    var onSwipeLeft: (() -> Unit)? = null

    /** 右滑（手指向右划）时回调 —— 切回上一页 */
    var onSwipeRight: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** 本次触摸的起点是否落在横向滚动区（生成历史）上；是则不参与切页，避免抢它的滑动 */
    private var startInScroller = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                startInScroller = hitsHorizontalScroller(this, ev.x, ev.y)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (startInScroller) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 横向位移要足够大，且明显大于纵向位移，否则视为纵向滚动，不拦截
                if (abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.8f) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> startInScroller = false
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop * 2 && abs(dx) > abs(dy) * 1.8f) {
                    if (dx < 0) onSwipeLeft?.invoke() else onSwipeRight?.invoke()
                }
                performClick()
                return true
            }
            MotionEvent.ACTION_MOVE -> return true
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** (x, y) 是否落在某个可横向滚动的子视图内（用于避让生成历史的横向滚动） */
    private fun hitsHorizontalScroller(v: View, x: Float, y: Float): Boolean {
        if (v is HorizontalScrollView) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i) ?: continue
                if (child.visibility != View.VISIBLE) continue
                val l = child.left.toFloat()
                val t = child.top.toFloat()
                if (x >= l && x <= l + child.width && y >= t && y <= t + child.height) {
                    if (hitsHorizontalScroller(child, x - l, y - t)) return true
                }
            }
        }
        return false
    }
}
