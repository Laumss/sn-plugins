package me.laumss.notipal.panels.pagepicker

class PagePickerPagination {
    var total: Int = 0
        private set
    var capacity: Int = 1
        private set
    var page: Int = 0
        private set
    val pageCount: Int get() = ((total + capacity - 1) / capacity).coerceAtLeast(1)
    val firstItem: Int get() = page * capacity
    val visibleItems: IntRange get() = firstItem until minOf(firstItem + capacity, total)

    fun configure(total: Int, capacity: Int, focusItem: Int? = null) {
        val anchor = focusItem ?: firstItem
        this.total = total.coerceAtLeast(0)
        this.capacity = capacity.coerceAtLeast(1)
        page = (anchor.coerceAtLeast(0) / this.capacity).coerceIn(0, pageCount - 1)
    }

    fun moveTo(page: Int) { this.page = page.coerceIn(0, pageCount - 1) }

    companion object {
        fun initialSelection(total: Int, currentPage: Int, saved: Collection<Int>): List<Int> {
            if (total <= 0) return emptyList()
            val valid = saved.filter { it in 0 until total }.distinct().sorted()
            return valid.ifEmpty { listOf(currentPage.coerceIn(0, total - 1)) }
        }
    }
}
