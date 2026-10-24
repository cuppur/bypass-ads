package li.songe.gkd.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.0 Release engineering policy (JVM): the product must expose exactly
 * versionName 1.0.1 / versionCode 101, the About page must read the real
 * version (no hard-coded 0.1.0), and release signing must never silently
 * fall back to the debug key.
 */
class ReleasePolicyTest {

    /** Repo root: JVM tests run with cwd = module dir (app/), walk up. */
    private val repoRoot: File = run {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile
        }
        dir
    }

    private fun file(relative: String): File = File(repoRoot, relative)

    @Test
    fun version_is_exactly_1_0_1_with_code_101() {
        val src = file("app/build.gradle.kts").readText()
        assertTrue("versionName must be exactly 1.0.1", src.contains("versionName = \"1.0.1\""))
        assertTrue("versionCode must be 101", src.contains("versionCode = 101"))
        assertFalse("no stale 0.x versionName", src.contains("versionName = \"0."))
    }

    @Test
    fun about_page_reads_real_version_not_hardcoded() {
        val src = file("app/src/main/kotlin/li/songe/gkd/bypass/BypassPages.kt").readText()
        assertFalse("About must not hard-code 0.1.0", src.contains("\"0.1.0\""))
        assertTrue("About must read the real META versionName", src.contains("META.versionName"))
    }

    @Test
    fun release_never_falls_back_to_debug_key() {
        val src = file("app/build.gradle.kts").readText()
        // The old behavior: gkdSigningConfig = if (...) create("gkd") else debug.
        // The new policy: no GKD_STORE_FILE -> null (unsigned release). The
        // release buildType must NEVER receive the debug key.
        assertTrue("release signingConfig must be nullable (unsigned release when no key)",
            src.contains("val gkdReleaseSigning = if (project.hasProperty(\"GKD_STORE_FILE\")"))
        assertTrue("release signingConfig must be the nullable var, not debug",
            src.contains("// null when no formal key is configured -> UNSIGNED release"))
        // Debug keeps the debug key explicitly:
        assertTrue("debug buildType must still use the debug key explicitly",
            src.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
    }

    @Test
    fun selfuse_script_has_no_hardcoded_version_string() {
        val src = file("tools/build_selfuse.ps1").readText()
        assertTrue("script must define the version once as a variable", src.contains("\$VERSION_NAME"))
        assertFalse("script must not hard-code the old 0.1.0", src.contains("0.1.0"))
        // The script must refuse to ship an unsigned/improper release:
        assertTrue("script must apksigner-verify", src.contains("apksigner"))
        assertTrue("script must check INTERNET absence", src.contains("android.permission.INTERNET"))
        assertTrue("script must verify the bundled rules SHA", src.contains("bundle SHA256 mismatch") || src.contains("bundleSha"))
    }

    @Test
    fun dist_is_gitignored() {
        val gi = file(".gitignore").readText()
        assertTrue("dist/ must be gitignored", "/dist/" in gi || "dist/" in gi)
    }

    @Test
    fun release_diagnostics_are_privacy_safe_and_active() {
        // P0-7: the release build records diagnostics (no debuggable gate).
        val src = file("app/src/main/kotlin/li/songe/gkd/bypass/BypassDiagnostics.kt").readText()
        assertFalse("release must not skip diagnostics via debuggable flag", src.contains("if (!META.debuggable) return"))
        assertTrue("diagnostics must be bounded", src.contains("MAX_EVENTS = 40"))
    }

    @Test
    fun backup_contract_covers_bypass_state() {
        // P0/P1-4: the backup must carry the Bypass self-owned state and the
        // restore must be authoritative (delete+insert for the Bypass config).
        val backup = file("app/src/main/kotlin/li/songe/gkd/util/BackupUtils.kt").readText()
        assertTrue("backup must include bypass_state.json", backup.contains("bypass_state.json"))
        assertTrue("backup must include the local import layer", backup.contains("bypass-local-import.json"))
        assertTrue("backup must include teach overrides", backup.contains("bypass-teach-overrides.json"))
        assertTrue("restore must rebuild the effective stack", backup.contains("rebuildEffectiveStack"))
        assertTrue("restore must be authoritative for the Bypass config", backup.contains("deleteBySubsId(BYPASS_SPLASH_SUBS_ID)"))
    }

    @Test
    fun registry_owner_semantics_present() {
        // P1-5: the service instance slot uses owner semantics.
        val service = file("app/src/main/kotlin/li/songe/gkd/service/A11yService.kt").readText()
        assertTrue("service must delegate to the ownership registry", service.contains("A11yInstanceRegistry"))
        val registry = file("app/src/main/kotlin/li/songe/gkd/service/A11yInstanceRegistry.kt").readText()
        assertTrue("registry must check identity on destroy", registry.contains("if (current === instance)"))
    }

    @Test
    fun load_clean_bundled_base_is_the_single_helper() {
        // P0-3 (3.3): every rebuild path (startup / import / restore / upgrade)
        // must go through the shared loadCleanBundledBase helper, never through
        // the current effective subscription.
        val appKt = file("app/src/main/kotlin/li/songe/gkd/App.kt").readText()
        val engine = file("app/src/main/kotlin/li/songe/gkd/bypass/GkdBypassEngine.kt").readText()
        assertTrue("helper lives on App.kt", appKt.contains("suspend fun loadCleanBundledBase()"))
        assertTrue("engine uses the shared helper", engine.contains("loadCleanBundledBase()"))
        assertTrue("initBundledSubs uses the shared helper", appKt.contains("val bundled = loadCleanBundledBase()"))
        assertTrue(
            "engine must not keep a private copy of the asset reader",
            !engine.contains("BYPASS_SPLASH_ASSETS_LOCAL_NAME"),
        )
    }

    @Test
    fun bundled_upgrade_does_not_cleanup_bypass_config() {
        val src = file("app/src/main/kotlin/li/songe/gkd/util/SubsState.kt").readText()
        assertTrue(
            "initBundledSub must skip cleanup for BYPASS_SPLASH_SUBS_ID",
            src.contains("if (subsId != BYPASS_SPLASH_SUBS_ID)"),
        )
        assertTrue(
            "initBundledSub must not write bundled-only payload as the Bypass effective stack",
            src.contains("do NOT write the bundled-only payload") ||
                src.contains("GkdBypassEngine always rebuilds"),
        )
    }

    @Test
    fun readme_does_not_claim_fixture_covers_many_apps() {
        val readme = file("README.md").readText()
        assertFalse(
            "README must not claim the public fixture covers many apps",
            readme.contains("覆盖大量常见 App"),
        )
        assertTrue("README must describe the local self-use bundle", readme.contains("full local bundle"))
        assertTrue("README must keep GPL-3.0 without a no-commercial add-on", readme.contains("GPL-3.0"))
        assertFalse("README must not add a no-commercial restriction", readme.contains("禁止商业使用"))
    }

    @Test
    fun ci_runs_privacy_policy_and_release_assemble() {
        val ci = file(".github/workflows/ci.yml").readText()
        assertTrue("CI must run test_privacy_policy.py", ci.contains("tools/test_privacy_policy.py"))
        assertTrue("CI must assemble gkd release (R8)", ci.contains(":app:assembleGkdRelease"))
        assertTrue("CI must keep debug / androidTest / fulltools", ci.contains(":app:assembleGkdDebugAndroidTest"))
    }

    @Test
    fun page_wide_ad_label_is_noop_and_gate_is_candidate_scoped() {
        val ctx = file("app/src/main/kotlin/li/songe/gkd/bypass/BypassAdContext.kt").readText()
        assertTrue("page-wide ad label must be a no-op", ctx.contains("Intentionally a no-op"))
        val engine = file("app/src/main/kotlin/li/songe/gkd/a11y/A11yRuleEngine.kt").readText()
        assertTrue(
            "engine must not call noteAdLabelFromWindow before the gate",
            !engine.contains("noteAdLabelFromWindow"),
        )
        assertTrue("engine must use candidate-scoped context", engine.contains("evaluateCandidateContext("))
        assertTrue("engine must capture immutable action evidence before performAction", engine.contains("captureActionEvidence("))
        assertTrue("engine must pass the snapshot into the verifier", engine.contains("sessionEvidence = actionEvidence"))
        assertTrue("engine must use semantic-sibling verifier helper", engine.contains("isSemanticSibling("))
    }

    @Test
    fun a11y_lifecycle_registers_on_create_and_connected() {
        val service = file("app/src/main/kotlin/li/songe/gkd/service/A11yService.kt").readText()
        assertTrue("onCreate must register the live instance", service.contains("override fun onCreate()"))
        assertTrue("onCreate must stamp the registry before callbacks", service.contains("A11yInstanceRegistry.connected(this)"))
        assertTrue("onDestroy must identity-check via the registry", service.contains("A11yInstanceRegistry.destroyed(this)"))
        val engine = file("app/src/main/kotlin/li/songe/gkd/bypass/GkdBypassEngine.kt").readText()
        assertTrue("UI status must consult system Bound state", engine.contains("checkA11ySystemBound"))
        assertTrue("UI status must use the shared resolver", engine.contains("resolveBypassServiceStatus("))
    }
}
