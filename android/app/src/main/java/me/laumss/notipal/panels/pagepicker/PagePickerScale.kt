package me.laumss.notipal.panels.pagepicker

import android.content.Context
import me.laumss.notipal.ui_common.filepicker.FilePickerScale

internal object PagePickerScale {

    fun px(ctx: Context, designPx: Number): Int = FilePickerScale.px(ctx, designPx)

    fun noteSp(ctx: Context, designPx: Number): Float = FilePickerScale.noteSp(ctx, designPx)

    fun dp(ctx: Context, v: Int): Int = FilePickerScale.dp(ctx, v)

    fun dp(ctx: Context, v: Float): Int = FilePickerScale.dp(ctx, v)

    fun sp(ctx: Context, v: Float): Float = FilePickerScale.sp(ctx, v)
}
