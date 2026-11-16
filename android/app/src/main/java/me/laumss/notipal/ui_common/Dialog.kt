package me.laumss.notipal.ui_common

import android.content.Context
import com.ratta.supernote.pluginlib.api.HostUIAPI
import com.ratta.supernote.pluginlib.callback.RattaDialogListener
import me.laumss.notipal.common.App
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.Main
import me.laumss.notipal.common.Strings

object DialogHooks {
    @Volatile var penGuard: ((token: Long, active: Boolean) -> Unit)? = null
    @Volatile var armLogMonitor: ((token: Long) -> Unit)? = null
}

object Dialogs {
    private const val TAG = "OwnedDialog"

    enum class ImageReceiveChoice { INSERT_NOW, KEEP, REJECT }

    private var nextToken = 0L
    private val held = LinkedHashSet<Long>()

    fun isShowing(): Boolean = held.isNotEmpty()

    private fun onMain(block: () -> Unit) {
        if (Main.isMain) block() else Main.post(block)
    }

    private fun hold(): Long {
        val token = ++nextToken
        held.add(token)
        DialogHooks.armLogMonitor?.invoke(token)
        DialogHooks.penGuard?.invoke(token, true)
        Log.i(TAG, "hold token=$token held=$held")
        return token
    }

    private fun release(token: Long, reason: String) {
        onMain {
            if (!held.remove(token)) return@onMain
            DialogHooks.penGuard?.invoke(token, false)
            Log.i(TAG, "release token=$token reason=$reason held=$held")
        }
    }

    fun onOwnedDismissLogged(token: Long) = release(token, "dismiss logged")

    private fun show(
        message: String,
        leftText: String,
        rightText: String,
        isSuccess: Boolean,
        tipStyle: Boolean,
        afterConfirm: () -> Unit,
        afterCancel: () -> Unit,
    ) {
        onMain {
            val token = hold()
            var finished = false
            fun finish(reason: String, callback: () -> Unit) {
                if (finished) return
                finished = true
                release(token, reason)
                callback()
            }
            val listener = object : RattaDialogListener {
                override fun onConfirm() = finish("confirm", afterConfirm)
                override fun onCancel() = finish("cancel", afterCancel)
            }
            try {

                val c: Context = App.react?.currentActivity ?: App.context
                if (tipStyle) HostUIAPI.getInstance().showTipDialog(c, isSuccess, message, listener)
                else HostUIAPI.getInstance().showRattaDialog(c, message, leftText, rightText, isSuccess, listener)
            } catch (error: Exception) {
                Log.w(TAG, "show failed token=$token", error)
                release(token, "show failed")
            }
        }
    }

    fun tip(text: String, onClose: () -> Unit = {}) = tip(text, isSuccess = false, onClose = onClose)

    fun tip(text: String, isSuccess: Boolean, onClose: () -> Unit = {}) {
        show(text, "", "", isSuccess, tipStyle = true, afterConfirm = onClose, afterCancel = onClose)
    }

    fun result(text: String, onClose: () -> Unit = {}) {
        show(text, "", Strings.get("confirm"), false, tipStyle = false, afterConfirm = onClose, afterCancel = onClose)
    }

    fun confirm(text: String, onResult: (Boolean) -> Unit) =
        confirm(text, Strings.get("cancel"), Strings.get("confirm"), onResult = onResult)

    fun confirm(text: String, cancelText: String, confirmText: String, isSuccess: Boolean = false, onResult: (Boolean) -> Unit) {
        show(text, cancelText, confirmText, isSuccess, tipStyle = false,
            afterConfirm = { onResult(true) }, afterCancel = { onResult(false) })
    }

    fun chooseImageReceive(text: String, onResult: (ImageReceiveChoice) -> Unit) {
        confirm(text, Strings.get("image_receive_more"), Strings.get("image_receive_insert_now")) { insertNow ->
            if (insertNow) {
                onResult(ImageReceiveChoice.INSERT_NOW)
                return@confirm
            }
            confirm(Strings.get("image_receive_keep_ask"), Strings.get("image_receive_reject"), Strings.get("confirm")) { keep ->
                onResult(if (keep) ImageReceiveChoice.KEEP else ImageReceiveChoice.REJECT)
            }
        }
    }
}
