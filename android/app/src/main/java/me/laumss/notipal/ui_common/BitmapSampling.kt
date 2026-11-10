package me.laumss.notipal.ui_common

internal object BitmapSampling {
    fun powerOfTwoSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        if (outH > reqH || outW > reqW) {
            val halfH = outH / 2; val halfW = outW / 2
            while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        }
        return sample
    }
}
