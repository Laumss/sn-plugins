package me.laumss.notipal

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import me.laumss.notipal.capture.Capture
import me.laumss.notipal.capture.CaptureModule
import me.laumss.notipal.clipboard.ClipSlots
import me.laumss.notipal.clipboard.ClipboardModule
import me.laumss.notipal.common.App
import me.laumss.notipal.common.CommonModule
import me.laumss.notipal.common.Log
import me.laumss.notipal.common.NotipalLogModule
import me.laumss.notipal.host.HostModule
import me.laumss.notipal.host.HostSession
import me.laumss.notipal.host.PenGuard
import me.laumss.notipal.image.ImageInsert
import me.laumss.notipal.image.ImageModule
import me.laumss.notipal.lasso.LassoModule
import me.laumss.notipal.lasso.PenLasso
import me.laumss.notipal.links.DocLinks
import me.laumss.notipal.links.LinksModule
import me.laumss.notipal.links.PluginLinks
import me.laumss.notipal.localsend.LocalSend
import me.laumss.notipal.localsend.LocalSendModule
import me.laumss.notipal.mosaic.MosaicModule
import me.laumss.notipal.palette.Palette
import me.laumss.notipal.palette.PaletteModule
import me.laumss.notipal.relay.InboxStore
import me.laumss.notipal.relay.RelayModule
import me.laumss.notipal.relay.Translation
import me.laumss.notipal.relay.Transcripts
import me.laumss.notipal.relay.WebLinkPanel
import me.laumss.notipal.textinsert.TextModule
import me.laumss.notipal.toolbar.SelectionHold
import me.laumss.notipal.toolbar.Settings
import me.laumss.notipal.toolbar.Toolbar
import me.laumss.notipal.toolbar.ToolbarModule
import me.laumss.notipal.ui_common.WindowRotation

class InklingPackages : ReactPackage {

    private val modules: List<Pair<String, (ReactApplicationContext) -> NativeModule>> = listOf(
        "NotipalLog" to ::NotipalLogModule,
        "NotipalCommon" to ::CommonModule,
        "NotipalHost" to ::HostModule,
        "NotipalMosaic" to ::MosaicModule,
        "NotipalToolbar" to ::ToolbarModule,
        "NotipalClipboard" to ::ClipboardModule,
        "NotipalLasso" to ::LassoModule,
        "NotipalImage" to ::ImageModule,
        "NotipalCapture" to ::CaptureModule,
        "NotipalLinks" to ::LinksModule,
        "NotipalPalette" to ::PaletteModule,
        "NotipalText" to ::TextModule,
        "NotipalLocalSend" to ::LocalSendModule,
        "NotipalRelay" to ::RelayModule,
    )

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {

        App.attach(reactContext)

        PenGuard.install()

        HostSession.install()

        WindowRotation.install()

        ImageInsert.install()

        DocLinks.install()

        Toolbar.install()
        Settings.install()

        SelectionHold.install()
        ClipSlots.install()

        PenLasso.install()

        Capture.install()

        PluginLinks.install()

        Palette.install()

        LocalSend.install()

        InboxStore.install()
        Transcripts.install()

        WebLinkPanel.install()

        Translation.install()

        RelayModule.install()

        return modules.mapNotNull { (name, create) ->
            try {
                create(reactContext)
            } catch (t: Throwable) {
                Log.e("InklingPackages", "register $name failed", t)
                null
            }
        }
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        emptyList()
}
