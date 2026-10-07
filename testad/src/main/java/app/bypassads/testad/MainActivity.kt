package app.bypassads.testad

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Debug/self-use deterministic splash scenes. This module is never part of
 * the Bypass Ads APK or its normal user navigation. */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private var warmScene = false
    private var returnedFromBackground = false

    override fun onPause() {
        super.onPause()
        if (warmScene) returnedFromBackground = true
    }

    override fun onResume() {
        super.onResume()
        if (warmScene && returnedFromBackground) {
            returnedFromBackground = false
            showScene("ae")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(root)
        intent.getStringExtra(EXTRA_SCENARIO)?.takeIf { it in scenarioNames }?.let(::showScene) ?: showPicker()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SCENARIO)?.takeIf { it in scenarioNames }?.let(::showScene)
    }

    private fun showPicker() {
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }
        list.addView(TextView(this).apply { text = "Bypass Ads · 开屏回归测试"; textSize = 21f; setTextColor(Color.BLACK) })
        list.addView(TextView(this).apply { text = "仅用于 debug/self-use；每个场景也可通过 adb extra 独立启动。"; setTextColor(Color.DKGRAY) })
        scenarioNames.forEach { mode -> sceneButton(list, scenarioLabel(mode), mode) }
        setScene(ScrollView(this).apply { addView(list) })
    }

    private fun sceneButton(parent: LinearLayout, label: String, mode: String) {
        parent.addView(Button(this).apply { text = label; setOnClickListener { showScene(mode) } }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun showScene(mode: String) {
        warmScene = mode == "ae"
        val scene = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(25, 31, 46))
            contentDescription = "BypassTestScene:$mode"
        }
        scene.addView(TextView(this).apply {
            // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS can still expose this caption.
            // Only the scene's real controls/labels may supply ad semantics.
            text = "测试场景 ${mode.uppercase()}"
            textSize = 17f
            setTextColor(Color.WHITE)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(24), dp(64), 0, 0) })
        when (mode) {
            "aj", "ak", "al", "am", "ap", "aq" -> {
                val delay = if (mode == "ap") 12_000L else 0L
                scene.postDelayed({
                    if (root.getChildAt(0) === scene) {
                        scene.addView(VisualAdView(mode), FrameLayout.LayoutParams(-1, -1))
                    }
                }, delay)
            }
            // A delay longer than the one-second Activity event throttle makes
            // repeated same-Activity resets deterministic, rather than racing
            // the real SDK's shorter readiness delay.
            "ai" -> {
                addAdLabeled(scene) {
                    addView(skipTarget(desc = "关闭弹屏", id = R.id.waiting_exit_control) {
                        showResult(mode)
                    }, topEndParams())
                }
                fun emitWindowEvent() {
                    scene.postDelayed({
                        if (root.getChildAt(0) !== scene) return@postDelayed
                        val manager = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                        if (!manager.isEnabled || !hasWindowFocus()) {
                            emitWindowEvent()
                            return@postDelayed
                        }
                        val event = android.view.accessibility.AccessibilityEvent.obtain(
                            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                        event.packageName = packageName
                        event.className = MainActivity::class.java.name
                        event.setSource(scene)
                        // The system can disable accessibility between the
                        // check and dispatch while the test harness restores
                        // services. That teardown race is not an app crash.
                        try {
                            root.parent?.requestSendAccessibilityEvent(root, event)
                        } catch (error: IllegalStateException) {
                            if (manager.isEnabled) throw error
                        }
                        emitWindowEvent()
                    }, 300L)
                }
                emitWindowEvent()
            }
            // Countdown events originate from a sibling of the close control,
            // after startup polling. The gate must see the fresh whole root.
            "ah" -> {
                val control = skipTarget(text = "跳过") { showResult(mode) }
                control.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun performAccessibilityAction(host: View, action: Int, args: android.os.Bundle?): Boolean {
                        if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) return true
                        return super.performAccessibilityAction(host, action, args)
                    }
                }
                scene.addView(control, topEndParams())
            }
            "ag" -> scene.postDelayed({
                if (root.getChildAt(0) !== scene) return@postDelayed
                addAdLabeled(scene) {
                    addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams())
                    val timer = TextView(this@MainActivity).apply {
                        text = "5秒"
                        setTextColor(Color.WHITE)
                        textSize = 14f
                    }
                    addView(timer, FrameLayout.LayoutParams(dp(45), dp(36), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(120); marginEnd = dp(150)
                    })
                    fun tick(n: Int) {
                        timer.postDelayed({
                            if (root.getChildAt(0) !== scene) return@postDelayed
                            timer.text = "$n 秒"
                            timer.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                            if (n > 0) tick(n - 1)
                        }, 500L)
                    }
                    tick(4)
                }
            }, 12_000L)
            // Late ad in the same Activity, past the old 10-second deadline.
            "ad", "ae" -> scene.postDelayed({
                if (root.getChildAt(0) === scene) {
                    scene.addView(skipTarget(text = "跳过 5") { showResult(mode) }, topEndParams())
                }
            }, 12_000L)
            // Two ads, same Activity and rule. The second starts after exit.
            "af" -> scene.addView(skipTarget(text = "跳过 5") {
                scene.removeAllViews()
                scene.addView(TextView(this).apply { text = "第一条已跳过，等待第二条广告"; setTextColor(Color.WHITE) })
                scene.postDelayed({
                    if (root.getChildAt(0) === scene) {
                        scene.addView(skipTarget(text = "跳过 5") { showResult(mode) }, topEndParams())
                    }
                }, 12_000L)
            }, topEndParams())
            "a" -> scene.addView(skipTarget(text = "跳过广告") { showResult(mode) }, topEndParams())
            "b" -> scene.addView(skipTarget(desc = "跳过") { showResult(mode) }, topEndParams())
            "c" -> scene.addView(skipTarget(id = R.id.splash_skip_control) { showResult(mode) }, topEndParams())
            "d" -> addClickableParent(scene, mode)
            "e" -> addGestureSafeTarget(scene, mode)
            "f" -> scene.addView(skipTarget(text = "NEXT") { showUnexpectedAction(mode) }, topEndParams())
            "g" -> scene.addView(skipTarget(text = "跳过片头") { showUnexpectedAction(mode) }, topEndParams())
            "h", "i" -> scene.addView(skipTarget(text = "跳过") { showUnexpectedAction(mode) }, topEndParams())
            "j" -> scene.addView(skipTarget(desc = "跳过") { showUnexpectedAction(mode) }, topEndParams())
            "k" -> addClickableParent(scene, mode)
            "l" -> {
                scene.addView(skipTarget(text = "跳过") { /* Accepted click, ad remains. */ }, topEndParams())
                val timer = TextView(this).apply { text = "25 秒"; setTextColor(Color.WHITE) }
                scene.addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                    topMargin = dp(120); marginStart = dp(30)
                })
                fun tick(remaining: Int) {
                    timer.postDelayed({
                        if (root.getChildAt(0) !== scene) return@postDelayed
                        timer.text = "$remaining 秒"
                        timer.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                        if (remaining > 0) tick(remaining - 1)
                    }, 1000L)
                }
                tick(24)
            }
            "m" -> addDelayedClickableTarget(scene, mode)
            "n" -> scene.addView(skipTarget(text = "跳过", clickable = false).apply {
                // The visual target intentionally has no accessibility node.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, topEndParams())
            "o" -> scene.addView(skipTarget(text = "跳过") { showResult(mode) }, topEndParams())
            // R6.2 strategy matrix scenes (p..w). Scenes with an explicit
            // "广告" label establish STRONG ad context for the V2 gate.
            // D  text=关闭          -> AGGRESSIVE+ may click
            "p" -> addAdLabeled(scene) { addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams()) }
            // E  desc=关闭广告      -> AGGRESSIVE+ may click
            "q" -> addAdLabeled(scene) { addView(skipTarget(desc = "关闭广告") { showResult(mode) }, topEndParams()) }
            // F  vid=ad_close       -> AGGRESSIVE+ may click
            "r" -> addAdLabeled(scene) { addView(skipTarget(id = R.id.ad_close) { showResult(mode) }, topEndParams()) }
            // G  text=×             -> AGGRESSIVE+ may click (small glyph)
            "s" -> addAdLabeled(scene) { addView(skipTarget(text = "×", desc = "close") { showResult(mode) }, tinyParams()) }
            // H  structural X (no text/desc View) -> CRAZY-only
            "t" -> addAdLabeled(scene) { addView(structuralXTarget { showResult(mode) }, tinyParams()) }
            // I  coordinate-only close (non-clickable node, no semantic) -> CRAZY-only
            "u" -> addAdLabeled(scene) { addView(structuralXTarget(clickable = false) { showResult(mode) }, tinyParams()) }
            // L  ordinary non-ad close button -> must NOT be clicked in any mode
            "w" -> scene.addView(skipTarget(text = "关闭") { showUnexpectedAction(mode) }, largeParams())
            // R6.3:
            // X  multi-stage Skip -> X -> Close: ONE session, 3 attempts, 1 success
            "x" -> addAdLabeled(scene) {
                addView(skipTarget(text = "跳过") {
                    addAdLabeled(scene) {
                        addView(skipTarget(text = "×") {
                            addAdLabeled(scene) {
                                addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams())
                            }
                        }, tinyParams())
                    }
                }, topEndParams())
            }
            // Y  ACTION_RESULT_TRUE_BUT_AD_REMAINS: action accepted but the ad
            //    stays -> must NOT be SUCCESS (OutcomeVerifier decides).
            "y" -> addAdLabeled(scene) { addView(skipTarget(text = "跳过") { /* ad remains */ }, topEndParams()) }
            // Z  external landing misclick: click jumps to the launcher/browser
            //    -> MISCLICK_SUSPECTED, session stops immediately.
            "z" -> addAdLabeled(scene) {
                addView(skipTarget(text = "关闭") {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }, topEndParams())
            }
            // AA trusted dedicated close (rule without bypassMode -> BUNDLED_DEDICATED)
            //    -> clicked even in CONSERVATIVE.
            "aa" -> scene.addView(skipTarget(text = "关闭") { showResult(mode) }, topEndParams())
            // AB teach fixture: plain clickable node with a close-ish desc.
            "ab" -> addAdLabeled(scene) {
                addView(skipTarget(text = "", desc = "关闭广告区域") { showResult(mode) }, tinyParams())
            }
            // AC P0-1: ordinary page + FAR banner "广告" + small ImageView.
            // Crazy must NOT act (far banner never mints STRONG for an
            // unrelated structural control).
            "ac" -> {
                scene.addView(TextView(this).apply {
                    text = "广告"
                    textSize = 12f
                    setTextColor(Color.rgb(180, 180, 180))
                }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                    setMargins(dp(24), 0, 0, dp(48))
                })
                scene.addView(structuralXTarget { showUnexpectedAction(mode) }, tinyParams())
            }
        }
        setScene(scene)
    }

    /** Adds a small "广告" label NEAR the candidate so the V2 gate sees
     * STRONG ad context for THIS ad (P0-1: a far page banner must never
     * upgrade an unrelated control). */
    private fun addAdLabeled(scene: FrameLayout, block: FrameLayout.() -> Unit) {
        scene.addView(TextView(this).apply {
            text = "广告"
            textSize = 12f
            setTextColor(Color.rgb(180, 180, 180))
        }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(88)
            marginEnd = dp(24)
        })
        scene.block()
    }

    private fun largeParams() = FrameLayout.LayoutParams(dp(520), dp(320), Gravity.CENTER).apply { topMargin = dp(120) }
    private fun tinyParams() = FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply { topMargin = dp(120); marginEnd = dp(24) }

    private fun addClickableParent(scene: FrameLayout, mode: String) {
        val parent = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { showResult(mode) }
            background = targetBackground()
        }
        parent.addView(skipTarget(text = "跳过", clickable = false), FrameLayout.LayoutParams(-1, -1))
        scene.addView(parent, topEndParams())
    }

    private fun addGestureSafeTarget(scene: FrameLayout, mode: String) {
        val target = skipTarget(text = "跳过", clickable = false)
        scene.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP &&
                event.x >= target.left && event.x <= target.right &&
                event.y >= target.top && event.y <= target.bottom
            ) {
                showResult(mode)
            }
            true
        }
        scene.addView(target, topEndParams())
    }

    private fun addDelayedClickableTarget(scene: FrameLayout, mode: String) {
        val target = skipTarget(text = "跳过", clickable = false)
        scene.addView(target, topEndParams())
        target.postDelayed({
            target.isClickable = true
            target.isFocusable = true
            target.setOnClickListener { showResult(mode) }
        }, 900)
    }

    private fun skipTarget(
        text: String = "",
        desc: String? = null,
        id: Int = View.NO_ID,
        clickable: Boolean = true,
        forceDesc: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) = TextView(this).apply {
        if (forceDesc) {
            // Structural-X scene: no text, no desc, plain ImageView-like node.
            this.text = ""
            contentDescription = null
        } else {
            this.text = text
            contentDescription = desc
        }
        this.id = id
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(37, 123, 229)); cornerRadius = dp(14).toFloat() }
        isClickable = clickable
        isFocusable = clickable
        onClick?.let { handler -> setOnClickListener { handler() } }
    }

    private fun targetBackground() = GradientDrawable().apply { setColor(Color.rgb(37, 123, 229)); cornerRadius = dp(14).toFloat() }

    /** A plain View with no text/desc: structural X / coordinate-only fixture. */
    private fun structuralXTarget(clickable: Boolean = true, onClick: (() -> Unit)? = null) =
        View(this).apply {
            background = targetBackground()
            isClickable = clickable
            isFocusable = clickable
            onClick?.let { handler -> setOnClickListener { handler() } }
        }
    private fun showResult(mode: String) = setScene(TextView(this).apply {
        text = "测试已跳过 (${mode.uppercase()})"
        textSize = 22f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(36, 112, 69))
    })

    /** Drawn pixels only: no virtual text nodes, descriptions or click action. */
    private inner class VisualAdView(private val mode: String) : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val created = android.os.SystemClock.elapsedRealtime()
        private val target = RectF()
        private val image = if (mode == "aq") BitmapFactory.decodeFile(java.io.File(getExternalFilesDir(null), "visual-calibration.png").path) else null
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(Color.rgb(190, 30, 35))
            val label = if (mode in setOf("ak", "al")) "关闭" else "跳过"
            val right = mode == "am"
            val count = (6 - (android.os.SystemClock.elapsedRealtime() - created) / 1_000L).coerceAtLeast(0)
            val x = if (right) width * 0.64f else width * 0.025f
            // Android 15+ enforces edge-to-edge for this target SDK. Read the
            // real status/cutout inset, including HyperOS' notification capsule.
            val insets = rootWindowInsets
            val systemTop = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets?.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())?.top ?: 0
            } else {
                @Suppress("DEPRECATION")
                insets?.systemWindowInsetTop ?: 0
            }
            val y = (systemTop + dp(24)).toFloat()
            val font = width * 0.041f
            if (image != null) {
                val imageWidth = width * 0.45f
                val imageHeight = image.height * imageWidth / image.width
                canvas.drawBitmap(image, null, RectF(0f, y, imageWidth, y + imageHeight), paint)
                target.set(width * 0.19f, y + width * 0.026f, width * 0.31f, y + width * 0.106f)
            } else {
                paint.color = Color.rgb(135, 30, 35)
                val pill = RectF(x, y, x + width * 0.29f, y + font * 1.65f)
                canvas.drawRoundRect(pill, font * 0.8f, font * 0.8f, paint)
                paint.color = Color.WHITE
                paint.textSize = font
                val baseline = y + font * 1.16f
                if (mode != "ak") canvas.drawText("广告", x + font * 0.40f, baseline, paint)
                canvas.drawText(label, x + font * 4.10f, baseline, paint)
                canvas.drawText("${count}秒", x + width * 0.32f, baseline, paint)
                target.set(x + font * 3.8f, y, x + width * 0.29f, y + font * 1.65f)
            }
            if (count > 0 || image != null) postInvalidateDelayed(250L)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP && target.contains(event.x, event.y)) {
                if (mode in setOf("ak", "am")) showUnexpectedAction(mode) else showResult(mode)
            }
            return true
        }
        override fun onDetachedFromWindow() { image?.recycle(); super.onDetachedFromWindow() }
    }
    private fun showUnexpectedAction(mode: String) = setScene(TextView(this).apply {
        text = "错误：发生了不应执行的动作 (${mode.uppercase()})"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(180, 52, 43))
    })
    private fun topEndParams() = FrameLayout.LayoutParams(dp(88), dp(40), Gravity.TOP or Gravity.END).apply { topMargin = dp(120); marginEnd = dp(24) }
    private fun setScene(view: View) { root.removeAllViews(); root.addView(view, FrameLayout.LayoutParams(-1, -1)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun scenarioLabel(mode: String) = when (mode) {
        "a" -> "A 精确规则：可点击跳过"
        "b" -> "B 通用规则：contentDescription 跳过"
        "c" -> "C 通用规则：view id 包含 skip"
        "d" -> "D 通用规则：可点击父节点"
        "e" -> "E 通用规则：不可点击节点的安全手势"
        "f" -> "F 非广告：NEXT"
        "g" -> "G 非广告：跳过片头"
        "h" -> "H 总开关关闭"
        "i" -> "I 本应用关闭"
        "j" -> "J 通用开屏保护关闭"
        "k" -> "K 不可点击目标 + 可点击父节点"
        "l" -> "L 动作返回成功但目标仍存在"
        "m" -> "M 延迟后才可点击"
        "n" -> "N 无 Accessibility 节点"
        "o" -> "O 导航栈验证 Hook"
        "p" -> "P 策略：text=关闭"
        "q" -> "Q 策略：desc=关闭广告"
        "r" -> "R 策略：vid=ad_close"
        "s" -> "S 策略：text=×"
        "t" -> "T 策略：结构 X（无文本）"
        "u" -> "U 策略：坐标-only 关闭"
        "w" -> "W 策略：普通关闭（非广告，禁止点击）"
        "x" -> "X 多阶段：跳过 → X → 关闭（1 会话 3 动作）"
        "y" -> "Y 动作成功但广告仍在（不得计成功）"
        "z" -> "Z 外部落地误触（跳到桌面，立即停止）"
        "aa" -> "AA 可信专用关闭（保守也可点）"
        "ab" -> "AB 教学节点固定场景"
        "ac" -> "AC 负例：远处 banner 广告 + 普通小 ImageView（疯狂也禁止）"
        "ah" -> "AH 控件点击假成功，实际手势才能关闭"
        "ai" -> "AI 等待期间反复通知同一窗口，等待不得重新计时"
        "ag" -> "AG 晚出现广告：倒计时更新来自关闭按钮旁边"
        "ad" -> "AD 进入 12 秒后出现的广告"
        "ae" -> "AE 后台常驻、返回 12 秒后再次弹广告"
        "af" -> "AF 同一页面连续两条广告"
        "aj" -> "AJ Canvas 开屏跳过，无无障碍文字"
        "ak" -> "AK 普通关闭及计时，无广告证据，不得点击"
        "al" -> "AL Canvas 广告关闭，旁边独立计时"
        "am" -> "AM 右上角退出区域，不得点击"
        "ap" -> "AP Canvas 广告进入 12 秒后出现"
        "aq" -> "AQ 临时真实广告按钮画面校准"
        else -> mode
    }

    companion object {
        const val EXTRA_SCENARIO = "scenario"
        private val scenarioNames = setOf(
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o",
            "p", "q", "r", "s", "t", "u", "w", "x", "y", "z", "aa", "ab", "ac", "ad", "ae", "af", "ag", "ah", "ai",
            "aj", "ak", "al", "am", "ap", "aq",
        )
    }
}
