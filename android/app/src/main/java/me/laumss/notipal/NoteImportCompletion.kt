package me.laumss.notipal

internal class NoteImportCompletion(private val startedAt: Long) {
    enum class Outcome { SUCCESS, IMPORT_FAILED, OPEN_FAILED }
    private var result: Boolean? = null
    private var finished = false
    private var entered = false

    fun observe(now: Long, visible: Boolean, reported: Boolean?): Outcome? {
        if (finished) return null
        entered = entered || visible
        if (reported != null) result = reported
        val outcome = when {
            result == false && visible -> Outcome.IMPORT_FAILED
            result == true && visible -> Outcome.SUCCESS
            !entered && now - startedAt >= OPEN_TIMEOUT_MS -> Outcome.OPEN_FAILED
            now - startedAt >= RESULT_TIMEOUT_MS -> Outcome.IMPORT_FAILED
            else -> null
        }
        if (outcome != null) finished = true
        return outcome
    }

    companion object {
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val RESULT_TIMEOUT_MS = 300_000L
    }
}
