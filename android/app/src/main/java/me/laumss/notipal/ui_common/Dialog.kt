package me.laumss.notipal.ui_common

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.ratta.supernote.pluginlib.api.HostUIAPI
import com.ratta.supernote.pluginlib.callback.RattaDialogListener
import me.laumss.notipal.FloatingPenGuard
import me.laumss.notipal.NativeLocale
import me.laumss.notipal.PenGuardOwner
import me.laumss.notipal.SubviewLogMonitor

object Dialog {

    private const val TAG = "OwnedDialog"

    enum class ImageReceiveChoice { INSERT_NOW, KEEP, REJECT }

    private val main = Handler(Looper.getMainLooper())

    private var nextToken = 0L
    private val held = LinkedHashSet<Long>()

    fun isShowing(): Boolean = held.isNotEmpty()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun hold(): Long {
        val token = ++nextToken
        held.add(token)
        SubviewLogMonitor.armOwnedDialog(token)
        FloatingPenGuard.setFullScreenActive(true, PenGuardOwner.ownedDialog(token))
        Log.i(TAG, "hold token=$token held=$held")
        return token
    }

    private fun release(token: Long, reason: String) {
        onMain {
            if (!held.remove(token)) return@onMain
            FloatingPenGuard.setFullScreenActive(false, PenGuardOwner.ownedDialog(token))
            Log.i(TAG, "release token=$token reason=$reason held=$held")
        }
    }

    fun onOwnedDismissLogged(token: Long) = release(token, "dismiss logged")

    private fun show(
        ctx: ReactApplicationContext,
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
                val c: android.content.Context = ctx.currentActivity ?: ctx
                if (tipStyle) HostUIAPI.getInstance().showTipDialog(c, isSuccess, message, listener)
                else HostUIAPI.getInstance().showRattaDialog(c, message, leftText, rightText, isSuccess, listener)
            } catch (error: Exception) {
                Log.w(TAG, "show failed token=$token", error)
                release(token, "show failed")
            }
        }
    }

    fun result(ctx: ReactApplicationContext, message: String, onDismiss: (() -> Unit)? = null) {
        val done = { onDismiss?.invoke(); Unit }
        show(ctx, message, "", NativeLocale.t("confirm"), false, tipStyle = false, afterConfirm = done, afterCancel = done)
    }

    fun tip(
        ctx: ReactApplicationContext,
        message: String,
        isSuccess: Boolean = false,
        onDismiss: (() -> Unit)? = null,
    ) {
        val done = { onDismiss?.invoke(); Unit }
        show(ctx, message, "", "", isSuccess, tipStyle = true, afterConfirm = done, afterCancel = done)
    }

    fun confirmResult(ctx: ReactApplicationContext, message: String, onResult: (Boolean) -> Unit) {
        confirmResult(
            ctx,
            message,
            NativeLocale.t("cancel"),
            NativeLocale.t("confirm"),
            onResult = onResult,
        )
    }

    fun confirmResult(
        ctx: ReactApplicationContext,
        message: String,
        cancelText: String,
        confirmText: String,
        isSuccess: Boolean = false,
        onResult: (Boolean) -> Unit,
    ) {
        show(ctx, message, cancelText, confirmText, isSuccess, tipStyle = false,
            afterConfirm = { onResult(true) }, afterCancel = { onResult(false) })
    }

    fun chooseImageReceive(
        ctx: ReactApplicationContext,
        message: String,
        onResult: (ImageReceiveChoice) -> Unit
    ) {
        confirmResult(ctx, message, NativeLocale.t("image_receive_more"), NativeLocale.t("image_receive_insert_now")) { insertNow ->
            if (insertNow) {
                onResult(ImageReceiveChoice.INSERT_NOW)
                return@confirmResult
            }
            confirmResult(ctx, NativeLocale.t("image_receive_keep_ask"),
                NativeLocale.t("image_receive_reject"), NativeLocale.t("confirm")) { keep ->
                onResult(if (keep) ImageReceiveChoice.KEEP else ImageReceiveChoice.REJECT)
            }
        }
    }
}
