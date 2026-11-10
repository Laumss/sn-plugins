package me.laumss.notipal.render

internal class NotePageCoordinates(
    pageWidth: Float, pageHeight: Float, displayWidth: Float, displayHeight: Float,
) {
    init {
        require(listOf(pageWidth, pageHeight, displayWidth, displayHeight).all { it.isFinite() && it > 0f })
    }
    val scaleX = pageWidth / displayWidth
    val scaleY = pageHeight / displayHeight

    fun contour(value: Float, index: Int): Float = value * if (index % 2 == 0) scaleX else scaleY

    companion object {

        fun pagePixels(pageWidth: Float, pageHeight: Float): NotePageCoordinates =
            NotePageCoordinates(pageWidth, pageHeight, pageWidth, pageHeight)
    }
}
