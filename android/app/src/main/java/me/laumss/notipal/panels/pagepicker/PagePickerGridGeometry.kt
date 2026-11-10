package me.laumss.notipal.panels.pagepicker

data class PagePickerGridGeometry(val cellWidth: Int, val thumbnailWidth: Int, val thumbnailHeight: Int, val cellHeight: Int) {
    companion object {
        fun fit(width: Int, height: Int, columns: Int, rows: Int, inset: Int, footerHeight: Int, bottomInset: Int = inset, topInset: Int = inset): PagePickerGridGeometry {
            val cellWidth = (width / columns.coerceAtLeast(1)).coerceAtLeast(1)
            val chrome = topInset + bottomInset + footerHeight
            val widthUnits = ((cellWidth - inset * 2) / 3).coerceAtLeast(1)
            val heightUnits = if (height > 0) ((height / rows.coerceAtLeast(1) - chrome) / 4).coerceAtLeast(1) else widthUnits
            val units = minOf(widthUnits, heightUnits)
            return PagePickerGridGeometry(cellWidth, units * 3, units * 4, units * 4 + chrome)
        }
    }
}
