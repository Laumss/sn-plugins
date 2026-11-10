package me.laumss.notipal

internal object MosaicPaletteSpec {
    fun presetsKey(mosaic: Boolean): String = if (mosaic) "mosaic_palette_presets" else "palette_presets"
    fun tiersKey(mosaic: Boolean): String = if (mosaic) "mosaic_palette_tier_prefs" else "palette_tier_prefs"

    fun penType(id: String): Int = when (id) {
        "ball" -> PenSizeSpec.CALLIGRAPHY
        "needle" -> PenSizeSpec.NEEDLE
        "pencil" -> PenSizeSpec.PENCIL
        else -> PenSizeSpec.MARKER
    }

    val penIds = listOf("ball", "needle", "pencil", "marker", "mk-lightGray", "mk-darkGray")

    fun color(id: String): String = when (id) {
        "mk-lightGray" -> "lightGray"
        "mk-darkGray" -> "darkGray"
        else -> "black"
    }

    fun normalize(id: String): String = when (id) {
        in penIds -> id
        "mk-gray" -> "mk-darkGray"
        else -> "ball"
    }

    fun iconAsset(id: String): String = when (id) {
        "mk-lightGray" -> "icons/pen/ic_mosaic_mark_pen_light_gray_svg.xml"
        "pencil" -> "icons/pen/ic_mosaic_pencil_svg.xml"
        "mk-darkGray" -> "icons/pen/ic_mosaic_mark_pen_dark_gray_svg.xml"
        else -> PenSizeSpec.penIconAsset(penType(id), color(id))
    }
}
