package me.laumss.notipal.ui_common.snux

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup

@SuppressLint("ViewConstructor")
internal class MasonryLayout(ctx: Context) : ViewGroup(ctx) {

    var columns = 3
    var gap = 0
    var rowGap = 0

    private var lefts = IntArray(0)
    private var tops = IntArray(0)

    fun columnWidth(width: Int): Int {
        val cols = columns.coerceAtLeast(1)
        return ((width - paddingLeft - paddingRight - gap * (cols - 1)) / cols).coerceAtLeast(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = columns.coerceAtLeast(1)
        val colW = columnWidth(width)
        val childW = MeasureSpec.makeMeasureSpec(colW, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        val bottoms = IntArray(cols) { -rowGap }
        lefts = IntArray(childCount)
        tops = IntArray(childCount)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childW, childH)
            var c = 0
            for (k in 1 until cols) if (bottoms[k] < bottoms[c]) c = k
            lefts[i] = paddingLeft + c * (colW + gap)
            tops[i] = paddingTop + bottoms[c] + rowGap
            bottoms[c] += rowGap + child.measuredHeight
        }
        setMeasuredDimension(width, paddingTop + bottoms.max().coerceAtLeast(0) + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until minOf(childCount, tops.size)) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.layout(lefts[i], tops[i], lefts[i] + child.measuredWidth, tops[i] + child.measuredHeight)
        }
    }
}
