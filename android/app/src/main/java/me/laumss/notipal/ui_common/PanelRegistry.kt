package me.laumss.notipal.ui_common

object PanelRegistry {
    private val live = LinkedHashSet<PanelBase>()
    private var owning = false

    var onActiveEdge: ((anyShowing: Boolean) -> Unit)? = null

    internal fun onShown(panel: PanelBase) {
        live += panel
        updateOwning()
    }
    internal fun onHidden(panel: PanelBase) {
        live -= panel
        updateOwning()
    }

    internal fun onOwnershipChanged() = updateOwning()

    private fun updateOwning() {
        val now = anyShowing()
        if (now == owning) return
        owning = now
        onActiveEdge?.invoke(now)
    }

    fun anyShowing(): Boolean = live.any { it.ownsScreen }

    fun anyLive(): Boolean = live.isNotEmpty()

    fun hideAll() = live.toList().forEach { it.hide() }

    fun hideForPanel() = live.toList().forEach { if (!it.keptThroughPanels) it.hide() }
    fun hideAllExcept(keep: PanelBase) = live.toList().forEach { if (it !== keep) it.hide() }
    fun suspendAll() = live.toList().forEach { it.suspendVisibility() }
    fun resumeAll() = live.toList().forEach { it.resumeVisibility() }

    fun handleRotation(): Boolean {
        var restoreToolbar = false
        for (panel in live.toList()) if (panel.onRotation()) restoreToolbar = true
        return restoreToolbar
    }
}
