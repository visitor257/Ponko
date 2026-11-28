// Ponko · (c) 2026 visitor257 · MIT License · github.com/visitor257/Ponko
package com.litertchat.app.video

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.litertchat.app.PonkoTheme
import com.litertchat.app.R
import java.io.File

/**
 * 帧序列播放器。
 *
 * 视频的产物是**一组 PNG**，不是 mp4 —— 宿主（2.2.1）没有编解码器，也不该在这一版里
 * 为了"看起来像视频"引入 MediaCodec。这里按 fps 轮播帧，配一个拖动条，
 * 够判断效果对不对；导出成视频文件是后面的事。
 *
 * 内存：帧不预加载。33 帧 832×480 解成 Bitmap 是 ~50 MB，而播放时一次只需要一张。
 * 用一个小缓存（相邻几帧）挡住来回拖动时的重复解码。
 */
class FramesView(
    private val act: Activity,
    private val pal: PonkoTheme,
) : LinearLayout(act) {

    private val image = ImageView(act).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(pal.mediaBg)
        minimumHeight = dp(120)
    }
    private val frameLabel = TextView(act).apply {
        textSize = 11f
        setTextColor(pal.subText)
    }
    private val playBtn = TextView(act).apply {
        textSize = 13f
        setTextColor(pal.primary)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), pal.border)
        }
        isClickable = true
        setOnClickListener { toggle() }
    }
    private val bar = SeekBar(act)

    private val handler = Handler(Looper.getMainLooper())
    private var frames: List<File> = emptyList()
    private var fps = 16
    private var index = 0
    private var playing = false
    private val cache = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)

    private val tick = object : Runnable {
        override fun run() {
            if (!playing || frames.isEmpty()) return
            show(index)
            index = (index + 1) % frames.size
            handler.postDelayed(this, (1000L / fps.coerceIn(1, 60)))
        }
    }

    init {
        orientation = VERTICAL
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(playBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(bar, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(frameLabel, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || frames.isEmpty()) return
                index = progress.coerceIn(0, frames.size - 1)
                show(index)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                pause()
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        setEnabledState(false)
    }

    fun setFrames(files: List<File>, fps: Int) {
        pause()
        frames = files.filter { it.isFile }
        this.fps = fps.coerceIn(1, 60)
        cache.values.forEach { runCatching { it.recycle() } }
        cache.clear()
        index = 0
        bar.max = (frames.size - 1).coerceAtLeast(0)
        bar.progress = 0
        setEnabledState(frames.isNotEmpty())
        if (frames.isNotEmpty()) show(0)
        updateLabel()
    }

    fun clear() = setFrames(emptyList(), 16)

    private fun setEnabledState(on: Boolean) {
        playBtn.isEnabled = on
        playBtn.alpha = if (on) 1f else 0.4f
        bar.isEnabled = on
        if (!on) {
            playBtn.text = act.getString(R.string.s_780)
            image.setImageDrawable(null)
        }
    }

    private fun toggle() {
        if (playing) pause() else play()
    }

    fun play() {
        if (frames.isEmpty()) return
        playing = true
        playBtn.text = act.getString(R.string.s_781)
        handler.post(tick)
    }

    fun pause() {
        playing = false
        playBtn.text = act.getString(R.string.s_780)
        handler.removeCallbacks(tick)
    }

    private fun show(i: Int) {
        if (frames.isEmpty()) return
        val idx = i.coerceIn(0, frames.size - 1)
        val bmp = cache[idx] ?: run {
            val b = runCatching { BitmapFactory.decodeFile(frames[idx].absolutePath) }.getOrNull()
            if (b != null) {
                if (cache.size >= 8) {
                    val eldest = cache.entries.firstOrNull()
                    if (eldest != null) {
                        cache.remove(eldest.key)
                        runCatching { eldest.value.recycle() }
                    }
                }
                cache[idx] = b
            }
            b
        }
        if (bmp != null) image.setImageBitmap(bmp)
        if (bar.progress != idx) bar.progress = idx
        updateLabel(idx)
    }

    private fun updateLabel(i: Int = index) {
        frameLabel.text = if (frames.isEmpty()) "" else
            "${(i).coerceIn(0, frames.size - 1) + 1} / ${frames.size}  ·  $fps fps"
    }

    /** 页面销毁时调：停 ticker、放 bitmap，否则 Handler 会攥着整页不放。 */
    fun release() {
        pause()
        handler.removeCallbacksAndMessages(null)
        cache.values.forEach { runCatching { it.recycle() } }
        cache.clear()
        frames = emptyList()
    }

    private fun dp(v: Int): Int =
        (v * act.resources.displayMetrics.density).toInt()
}
