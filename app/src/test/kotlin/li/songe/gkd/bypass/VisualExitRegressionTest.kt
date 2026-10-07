package li.songe.gkd.bypass

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class VisualExitRegressionTest {
    private val w = 1200
    private val h = 2608
    private val ads = BypassVisualText("广告", Bounds(70, 170, 145, 220))
    private val exit = BypassVisualText("跳过", Bounds(250, 170, 335, 220))
    private val timer = BypassVisualText("4秒", Bounds(400, 170, 475, 220))
    private fun inspect(vararg spans: BypassVisualText) = BypassVisualExitPolicy.inspect(spans.toList(), w, h)
    private fun proof() = inspect(ads, exit, timer)!!

    @Test fun separateTimerAndAdLabelIdentifyTheExactExit() {
        assertEquals(exit.bounds, proof().bounds)
        assertEquals(4, proof().countdown)
        assertEquals("跳过", proof().label)
    }
    @Test fun closesAlsoRequireBothNearbySignals() {
        val close = exit.copy(text = "关闭")
        assertEquals("关闭", inspect(ads, close, timer)?.label)
        assertNull(inspect(close, timer))
        assertNull(inspect(ads, close))
    }
    @Test fun aBareSkipDoesNotProvideEnoughVisualProof() {
        assertNull(inspect(exit))
        assertNull(inspect(exit, timer))
        assertNull(inspect(exit, ads))
    }
    @Test fun theRightCapsuleIsExcludedEvenWithPlausibleAdText() {
        fun right(v: BypassVisualText) = v.copy(bounds = v.bounds.copy(l = v.bounds.l + 650, r = v.bounds.r + 650))
        assertNull(inspect(right(ads), right(exit), right(timer)))
        assertNull(inspect(ads, exit.copy(text = "×"), timer))
    }
    @Test fun unrelatedLabelsAndCountdownsCannotLendProof() {
        assertNull(inspect(ads.copy(bounds = Bounds(70, 460, 145, 510)), exit, timer))
        assertNull(inspect(ads, exit, timer.copy(bounds = Bounds(400, 390, 475, 440))))
        assertNull(inspect(ads, exit, timer.copy(bounds = Bounds(900, 170, 975, 220))))
    }
    @Test fun paymentAndNavigationTextNeverBecomeExits() {
        listOf("关闭支付", "关闭验证码", "确认支付", "跳过片头", "跳过并同意", "退出小程序", "下一步", "广告跳过").forEach {
            assertNull(it, inspect(ads, exit.copy(text = it), timer))
        }
    }
    @Test fun largePageTextAndStatusBarTextAreRejected() {
        assertNull(inspect(ads, exit.copy(bounds = Bounds(0, 100, 539, 300)), timer))
        assertNull(inspect(ads, exit.copy(bounds = Bounds(250, 5, 335, 55)), timer))
    }
    @Test fun shortChineseAndEnglishTimersAreAccepted() {
        listOf("4 秒", "4s", "4sec", "4").forEach { assertEquals(4, inspect(ads, exit, timer.copy(text = it))?.countdown) }
        listOf("31秒", "60s", "123", "12345678901", "继续4秒", "-1").forEach {
            assertNull(inspect(ads, exit, timer.copy(text = it)))
        }
    }
    @Test fun whitespaceAndExactSymbolBoxesPreserveTheExitCenter() {
        assertEquals(exit.bounds, inspect(ads, exit.copy(text = "跳 过"), timer)?.bounds)
        assertNull(inspect(ads, exit.copy(text = "广告｜跳过"), timer))
    }
    @Test fun physicalPixelsMapToLogicalGestureCoordinates() {
        val mapped = BypassVisualExitPolicy.mapBounds(exit.bounds, w, h, 1224, 2700)!!
        assertEquals(255, mapped.l)
        assertEquals(341, mapped.r)
        assertEquals(175, mapped.t)
        assertEquals(227, mapped.b)
        assertNull(BypassVisualExitPolicy.mapBounds(exit.bounds, 2608, 1200, 1200, 2608))
        assertNull(BypassVisualExitPolicy.mapBounds(exit.bounds, 0, h, w, h))
    }
    @Test fun aFreshSecondFrameIsRequiredBeforeEveryTap() {
        val a = proof()
        assertTrue(BypassVisualExitPolicy.canAct(a, a.copy(countdown = 3), 1000, 1200))
        assertFalse(BypassVisualExitPolicy.canAct(a, a, 1000, 1751))
        assertFalse(BypassVisualExitPolicy.canAct(a, a.copy(countdown = 1), 1000, 1200))
        assertFalse(BypassVisualExitPolicy.canAct(a, a.copy(countdown = 5), 1000, 1200))
        assertFalse(BypassVisualExitPolicy.canAct(a, a.copy(bounds = Bounds(120, 170, 205, 220)), 1000, 1200))
    }
    @Test fun aNaturalCountdownExpiryNeverCountsAsSuccess() {
        val a = proof().copy(countdown = 2)
        assertTrue(BypassVisualExitPolicy.verifyAbsenceBeforeExpiry(a, 1000, 1400, 1750))
        assertFalse(BypassVisualExitPolicy.verifyAbsenceBeforeExpiry(a, 1000, 1750, 2100))
        assertFalse(BypassVisualExitPolicy.verifyAbsenceBeforeExpiry(a, 1000, 1400, 1500))
    }
    @Test fun verificationChecksTheOriginalExitRegionAndItsAdLabel() {
        val a = proof()
        assertTrue(BypassVisualExitPolicy.sameRegionHasEvidence(listOf(ads), a))
        assertTrue(BypassVisualExitPolicy.sameRegionHasEvidence(listOf(exit), a))
        assertFalse(BypassVisualExitPolicy.sameRegionHasEvidence(listOf(timer), a))
        assertFalse(BypassVisualExitPolicy.sameRegionHasEvidence(listOf(ads.copy(bounds = Bounds(70, 460, 145, 510))), a))
    }
    @Test fun knownMiniActivitiesAreRequiredAndTheTestHostIsDebugOnly() {
        assertTrue(BypassVisualExitPolicy.isSupportedHost("com.eg.android.AlipayGphone", "com.alibaba.ariver.app.ui.XRiverActivity"))
        assertTrue(BypassVisualExitPolicy.isSupportedHost("com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"))
        assertFalse(BypassVisualExitPolicy.isSupportedHost("com.eg.android.AlipayGphone", "com.alipay.mobile.quinox.LauncherActivity"))
        assertFalse(BypassVisualExitPolicy.isSupportedHost("app.bypassads.testad", "app.bypassads.testad.MainActivity"))
        assertTrue(BypassVisualExitPolicy.isSupportedHost("app.bypassads.testad", "app.bypassads.testad.MainActivity", debug = true))
    }
    @Test fun theSharedGateRejectsForgedVisualCandidatesAndConservativeMode() {
        val p = BypassRulePolicy(BypassRuleTrust.BYPASS_OVERRIDE, BypassAdStrategyMode.AGGRESSIVE, true, false, 3)
        fun gate(mode: BypassAdStrategyMode, verified: Boolean, policy: BypassRulePolicy = p) = BypassStrategyGate.evaluateExecution(
            BypassExitCandidateType.LOCAL_VISUAL_EXIT, "com.eg.android.AlipayGphone", "com.alibaba.ariver.app.ui.XRiverActivity",
            85, 50, mode.policy, policy, BypassAdContextLevel.STRONG, inWindow = false, verifiedVisualExit = verified)
        assertNull(gate(BypassAdStrategyMode.AGGRESSIVE, true))
        assertEquals(BypassRejectReason.STRATEGY_GATE, gate(BypassAdStrategyMode.CONSERVATIVE, true))
        assertEquals(BypassRejectReason.NEGATIVE_SEMANTIC, gate(BypassAdStrategyMode.AGGRESSIVE, false))
        assertNotNull(gate(BypassAdStrategyMode.CRAZY, true, p.copy(trust = BypassRuleTrust.LOCAL_IMPORT_DEDICATED)))
    }
    @Test fun nodeAndVisualActionsCannotAcquireTheSameWindowTogether() {
        val arbiter = BypassExecutionArbiter()
        val workers = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        try {
            val tasks = (0..7).map { workers.submit<Long?> { ready.countDown(); start.await(); arbiter.tryAcquire() } }
            ready.await(); start.countDown()
            val owners = tasks.mapNotNull { it.get() }
            assertEquals(1, owners.size)
            assertTrue(arbiter.busy)
            assertTrue(arbiter.release(owners.single()))
            val newer = arbiter.tryAcquire()!!
            assertFalse(arbiter.release(owners.single()))
            assertTrue(arbiter.busy)
            assertTrue(arbiter.release(newer))
        } finally { workers.shutdownNow() }
    }
}
