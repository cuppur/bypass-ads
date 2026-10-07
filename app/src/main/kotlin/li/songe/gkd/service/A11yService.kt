package li.songe.gkd.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context.WINDOW_SERVICE
import android.provider.Settings
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import li.songe.gkd.a11y.A11yCommonImpl
import li.songe.gkd.a11y.A11yRuleEngine
import li.songe.gkd.a11y.topActivityFlow
import li.songe.gkd.a11y.updateTopActivity
import li.songe.gkd.shizuku.shizukuContextFlow
import li.songe.gkd.store.updateEnableAutomator
import li.songe.gkd.util.AndroidTarget
import li.songe.gkd.util.AutomatorModeOption
import li.songe.gkd.util.DefaultA11yLifeImpl
import li.songe.gkd.util.LogUtils
import li.songe.gkd.util.OnA11yLife
import li.songe.gkd.util.componentName
import li.songe.gkd.util.runMainPost
import li.songe.gkd.util.toast
import kotlin.coroutines.resume

@SuppressLint("AccessibilityPolicy")
abstract class A11yService : AccessibilityService(), OnA11yLife by DefaultA11yLifeImpl(),
    A11yCommonImpl {
    override val mode get() = AutomatorModeOption.A11yMode
    override val windowNodeInfo: AccessibilityNodeInfo? get() = rootInActiveWindow
    override val windowInfos: List<AccessibilityWindowInfo> get() = windows
    @Volatile
    final override var screenshotFailureCode: Int? = null
        private set
    override suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (AndroidTarget.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                application.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onFailure(errorCode: Int) {
                        screenshotFailureCode = errorCode
                        if (cont.isActive) {
                            cont.resume(null)
                        }
                    }

                    override fun onSuccess(screenshot: ScreenshotResult) {
                        screenshotFailureCode = null
                        try {
                            if (cont.isActive) {
                                val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                cont.resume(bitmap) { _, value, _ -> value?.recycle() }
                            }
                        } finally {
                            screenshot.hardwareBuffer.close()
                        }
                    }
                }
            )
        } else {
            cont.resume(null)
        }
    }

    override val ruleEngine by lazy { A11yRuleEngine(this) }

    override fun onCreate() {
        // Register BEFORE the callback list: HyperOS recovery/upgrade can bind
        // the service without a later onServiceConnected. The product UI must
        // not stay on "正在恢复" while the system already reports Bound.
        A11yInstanceRegistry.connected(this)
        onCreated()
        li.songe.gkd.bypass.GkdBypassEngine.noteA11ySystemChanged()
    }
    override fun onServiceConnected() {
        A11yInstanceRegistry.connected(this)
        if (!connected) onA11yConnected()
        li.songe.gkd.bypass.GkdBypassEngine.noteA11ySystemChanged()
    }
    override fun onInterrupt() {}
    override fun onDestroy() {
        onDestroyed()
        // Identity-checked: an old instance must never clear a newer one.
        A11yInstanceRegistry.destroyed(this)
        li.songe.gkd.bypass.GkdBypassEngine.noteA11ySystemChanged()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Any live event is proof the service is Bound and working — keep the
        // registry in sync even when HyperOS skipped onServiceConnected. Only
        // tick the product UI on the first event after a gap so we do not
        // recompute status on every window-content change.
        val wasRunning = A11yInstanceRegistry.isRunning.value
        A11yInstanceRegistry.connected(this)
        if (!wasRunning) {
            li.songe.gkd.bypass.GkdBypassEngine.noteA11ySystemChanged()
        }
        // A Bound HyperOS service can receive live events after an upgrade
        // without onServiceConnected. Initialize the engine and visual worker
        // from that proof of connection too; the UI registry alone is not enough.
        if (!connected) onA11yConnected()
        ruleEngine.onA11yEvent(event)
    }

    val startTime = System.currentTimeMillis()
    override var justStarted: Boolean = true
        get() {
            if (field) {
                field = System.currentTimeMillis() - startTime < 3_000
            }
            return field
        }

    private var tempShutdownFlag = false

    override fun shutdown(temp: Boolean) {
        if (temp) {
            tempShutdownFlag = true
        }
        disableSelf()
    }

    private var destroyed = false
    private var connected = false

    val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    init {
        useLogLifecycle()
        // P1-5 (HyperOS destroy/rebind race): instance ownership and the
        // running flag live in A11yInstanceRegistry. useAliveFlow is NOT used
        // for isRunning: an old instance A destroyed after new instance B
        // connected must never clear B (A.onDestroy must not flip
        // isRunning=false while B is alive).
        //
        // HyperOS can bind the service after an APK upgrade / recovery without
        // delivering onServiceConnected (the system treats it as a continuation
        // of the existing bind). Mark the instance live on onCreated as well
        // so the UI does not stay stuck on "正在恢复" while dumpsys already
        // shows the service Bound. onServiceConnected still refreshes
        // lastConnectedAt.
        onCreated { A11yInstanceRegistry.connected(this) }
        onA11yConnected { A11yInstanceRegistry.connected(this) }
        onDestroyed { A11yInstanceRegistry.destroyed(this) }
        onCreated {
            if (currentAppUseA11y) {
                updateEnableAutomator(true)
            } else {
                toast("当前为自动化模式，无障碍将自动关闭", forced = true)
                runMainPost(1) { shutdown(true) }
            }
        }
        onDestroyed {
            if (tempShutdownFlag) {
                toast("无障碍局部关闭")
            } else if (isStillAuthorized()) {
                // onDestroy is also delivered for system reclaim/rebind. Do
                // not overwrite the user's persisted enabled intent in that
                // case; onServiceConnected will publish the new connection.
                LogUtils.d("A11yService destroyed while still authorized; awaiting system rebind")
            } else {
                toast("无障碍已关闭")
                updateEnableAutomator(false)
            }
        }
        useAliveOverlayView()
        onCreated { StatusService.autoStart() }
        onDestroyed {
            synchronized(topActivityFlow) {
                shizukuContextFlow.value.topCpn()?.let { cpn ->
                    // com.android.systemui
                    if (!topActivityFlow.value.sameAs(cpn.packageName, cpn.className)) {
                        updateTopActivity(cpn.packageName, cpn.className)
                    }
                }
            }
        }
        onDestroyed { destroyed = true }
        onA11yConnected {
            connected = true
            lastConnectedAt.value = System.currentTimeMillis()
            toast("无障碍已启动")
            if (currentAppUseA11y) {
                ruleEngine.onA11yConnected()
            }
        }
        onCreated {
            runMainPost(3000) {
                if (!(destroyed || connected)) {
                    toast("无障碍启动超时，请尝试关闭重启", forced = true)
                }
            }
        }
    }

    companion object {
        val a11yCn by lazy { SelectToSpeakService::class.componentName }

        /** P1-5: delegated to the ownership registry (old-instance destroy
         * can never clear a newer connected instance). */
        val isRunning = A11yInstanceRegistry.isRunning
        val lastConnectedAt = MutableStateFlow(0L)

        /** The live service instance (P1-5: single-owner registry). */
        val instance: A11yService?
            get() = A11yInstanceRegistry.currentInstance() as? A11yService
    }

    private fun isStillAuthorized(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it == a11yCn.flattenToString() }
    }
}

private fun A11yService.useAliveOverlayView() {
    val context = this
    var aliveView: View? = null
    val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    fun removeA11View() {
        if (aliveView != null) {
            wm.removeView(aliveView)
            aliveView = null
        }
    }

    fun addA11View() {
        removeA11View()
        val tempView = View(context)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags =
                flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.START or Gravity.TOP
            width = 1
            height = 1
            packageName = context.packageName
        }
        try {
            // 某些设备 android.view.WindowManager$BadTokenException
            wm.addView(tempView, lp)
            aliveView = tempView
        } catch (e: Throwable) {
            aliveView = null
            LogUtils.d(e)
            toast("添加无障碍保活失败\n请尝试重启无障碍")
        }
    }
    onA11yConnected { addA11View() }
    onDestroyed { removeA11View() }
}
