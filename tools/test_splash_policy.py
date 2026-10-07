#!/usr/bin/env python3
"""Regression checks for the Bypass-owned generic splash fallback policy.

This intentionally tests the generated selector contract rather than trying
to duplicate GKD's selector engine. The engine remains upstream-owned; these
checks make the Bypass build policy auditable before an APK reaches a device.
"""

import importlib.util
import json
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
MODULE_PATH = ROOT / "tools" / "build_splash_bundle.py"
SPEC = importlib.util.spec_from_file_location("build_splash_bundle", MODULE_PATH)
assert SPEC and SPEC.loader
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


def test_fallback_selector_contract() -> None:
    group = builder.GENERIC_FALLBACK_GROUP
    serialized = json.dumps(group, ensure_ascii=False)

    assert group["name"] == "开屏广告-通用跳过"
    assert "matchTime" not in group
    assert group["forcedTime"] == 10000
    assert "text*=" in serialized and "跳过" in serialized
    assert "desc*=" in serialized
    assert "vid~=" in serialized and ".*skip.*" in serialized
    assert "@[clickable=true]" in serialized
    assert '"action": "clickCenter"' in serialized

    for unsafe in ("NEXT", "跳过片头", "跳过视频", "阅读并同意"):
        assert unsafe.lower() in serialized.lower()


def test_source_global_filter_contract() -> None:
    assert builder.is_splash_global_group("开屏广告")
    assert builder.is_splash_global_group("开屏广告-全局")
    assert not builder.is_splash_global_group("更新提示")
    assert not builder.is_splash_global_group("权限提示")


def test_source_global_reinforcement_contract() -> None:
    groups = [{"key": 0, "name": "开屏广告-全局", "rules": [{"key": 0}]}]
    assert builder.reinforce_source_global_groups(groups) == 6
    assert builder.reinforce_source_global_groups(groups) == 0
    serialized = json.dumps(groups[0], ensure_ascii=False)
    assert "Bypass Ads 可点击父节点补强" in serialized
    assert "Bypass Ads 安全手势补强" in serialized
    # Strategy-gated close / X / structural reinforcement rules
    # carry structured bypassMode metadata (never name-parsed at runtime).
    assert "Bypass Ads 策略化关闭补强-Aggressive" in serialized
    assert "Bypass Ads 策略化 X 补强-Aggressive" in serialized
    assert "Bypass Ads 策略化结构关闭补强-Crazy" in serialized
    assert '"bypassMode": "AGGRESSIVE"' in serialized
    assert '"bypassMode": "CRAZY"' in serialized
    assert '"action": "clickCenter"' in serialized
    for unsafe in ("NEXT", "跳过片头", "跳过视频", "阅读并同意"):
        assert unsafe.lower() in serialized.lower()


def test_override_group_policy_is_preserved() -> None:
    apps = []
    added = builder.apply_overrides(
        apps,
        {
            "apps": [
                {
                    "id": "app.bypassads.testad",
                    "groups": [
                        {
                            "name": "开屏广告-确定性测试",
                            "order": -20,
                            "ignoreGlobalGroupMatch": True,
                            "rules": [{"key": 0, "matches": ["[text=\"跳过广告\"]"]}],
                        }
                    ],
                }
            ]
        },
    )
    assert added == 1
    group = apps[0]["groups"][0]
    assert group["order"] == -20
    assert group["ignoreGlobalGroupMatch"] is True


def test_multi_source_union_dedupe_and_conflict_report() -> None:
    source_a = ROOT / "tools" / "fixtures" / "source_a.json"
    source_b = ROOT / "tools" / "fixtures" / "source_b.json"
    parsed = [
        (builder.load_subscription(source_a.read_text(encoding="utf-8")), source_a),
        (builder.load_subscription(source_b.read_text(encoding="utf-8")), source_b),
    ]
    merged, report = builder.merge_sources(parsed)
    assert {app["id"] for app in merged["apps"]} == {"fixture.app1", "fixture.shared", "fixture.app3"}
    assert report["coverageDiff"]["primaryOnlyApps"] == ["fixture.app1"]
    assert report["coverageDiff"]["secondaryOnlyApps"] == ["fixture.app3"]
    assert report["coverageDiff"]["sharedApps"] == ["fixture.shared"]
    assert len(report["conflicts"]) == 1
    shared = next(app for app in merged["apps"] if app["id"] == "fixture.shared")
    assert shared["groups"][0]["rules"][0]["matches"] == ['[vid="skip_a"]']


def test_miniprogram_override_alternatives_are_disjunctions() -> None:
    overrides = json.loads(builder.OVERRIDES_PATH.read_text(encoding="utf-8"))
    apps = {app["id"]: app for app in overrides["apps"]}
    for package, keys in {
        "com.tencent.mm": {200, 201, 203},
        "com.eg.android.AlipayGphone": {210},
    }.items():
        rules = {rule["key"]: rule for group in apps[package]["groups"] for rule in group["rules"]}
        for key in keys:
            rule = rules[key]
            # GKD matches arrays require ALL selectors; these alternatives
            # must match independently (description OR text OR SDK id).
            assert "matches" not in rule, (package, key)
            assert len(rule["anyMatches"]) >= 2, (package, key)
    skip = apps["com.tencent.mm"]["groups"][0]["rules"][0]
    serialized = json.dumps(skip, ensure_ascii=False)
    assert 'desc*=' in serialized and 'text*=' in serialized
    assert 'length<16' in serialized
    # A real existing upstream group must not erase the override host scope.
    old_activity = ".plugin.appbrand.ui.AppBrandUI"
    existing = [{"id": "com.tencent.mm", "groups": [{
        "key": 10, "name": "开屏广告-微信小程序", "activityIds": [old_activity],
        "rules": [{"key": 0, "matches": '[text="跳过"]'}],
    }]}]
    builder.apply_overrides(existing, overrides)
    group = existing[0]["groups"][0]
    assert group["activityIds"] == [old_activity]
    assert all(".plugin.appbrand.ui.AppBrandPluginUI" in rule["activityIds"]
               for rule in group["rules"] if rule["key"] >= 200)
    assert builder.apply_overrides(existing, overrides) == 0
    source = {"apps": [{"id": "com.eg.android.AlipayGphone", "groups": [{
        "name": "开屏广告-小程序开屏广告", "activityIds": "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity$",
        "rules": [{"matches": "[text=\"跳过\"]"}],
    }]}]}
    prepared, _ = builder.prepare_source(source, {})
    assert prepared[0]["groups"][0]["matchRoot"] is True
    assert "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity" in prepared[0]["groups"][0]["activityIds"]
    # Check what is actually emitted, not only the override source.
    merged_apps = []
    builder.apply_overrides(merged_apps, overrides)
    for app in merged_apps:
        if app["id"] not in ("com.tencent.mm", "com.eg.android.AlipayGphone"):
            continue
        for group in app["groups"]:
            for rule in group["rules"]:
                if rule["key"] in {200, 201, 203, 210}:
                    assert rule.get("anyMatches") and not rule.get("matches")


def test_multi_source_identical_group_is_deduplicated() -> None:
    source_a = ROOT / "tools" / "fixtures" / "source_a.json"
    data_a = builder.load_subscription(source_a.read_text(encoding="utf-8"))
    with tempfile.TemporaryDirectory() as temp:
        second = Path(temp) / "source-a-copy.json"
        second.write_text(source_a.read_text(encoding="utf-8"), encoding="utf-8")
        merged, report = builder.merge_sources([(data_a, source_a), (data_a, second)])
    assert len(merged["apps"]) == 2
    assert report["deduplicatedGroups"] == 2


def test_rule_audit_and_delayed_host_contracts() -> None:
    valid = {"apps": [{"id": "test", "groups": [{"key": 0, "rules": ["[text=\"跳过\"]", {"matches": "[vid=\"skip\"]"}]}]}]}
    assert not builder.validate_rule_contracts(valid)
    invalid = {"globalGroups": [{"key": 0, "rules": [{"key": 1, "matches": "[text=\"跳过\"]"}, {"key": 1}]}]}
    errors = builder.validate_rule_contracts(invalid)
    assert any("重复规则编号" in e for e in errors)
    assert any("无匹配条件" in e for e in errors)
    overrides = json.loads(builder.OVERRIDES_PATH.read_text(encoding="utf-8"))
    for app in overrides["apps"]:
        if app["id"] in ("com.tencent.qqmusic", "com.tencent.mm", "com.eg.android.AlipayGphone"):
            for group in app["groups"]:
                assert "matchTime" not in group
                assert group["forcedTime"] == 10000
                for rule in group["rules"]:
                    if app["id"] == "com.tencent.mm" and rule["key"] in {200, 201}:
                        assert rule["actionDelay"] == 800  # mini-program SDK readiness
                        assert rule["action"] == "clickCenter"
                    else:
                        assert rule.get("actionDelay", 0) <= 150
    for rule in builder.SOURCE_GLOBAL_REINFORCEMENT_RULES:
        assert "excludeMatches" not in rule  # candidate veto, not whole-page veto
    for shorthand in ("[text=\"跳过\"]", {"matches": "[text=\"跳过\"]"}):
        data = {"apps": [{"id": "test", "groups": [{"key": 0, "name": "开屏广告", "rules": shorthand}]}]}
        apps, _ = builder.prepare_source(data, {})
        assert len(apps[0]["groups"][0]["rules"]) == 1
        assert not builder.validate_rule_contracts({"apps": apps})
    assert not builder._same_rule({"matches": "[text=\"跳过\"]", "action": "clickCenter"}, "[text=\"跳过\"]")


if __name__ == "__main__":
    test_fallback_selector_contract()
    test_source_global_filter_contract()
    test_source_global_reinforcement_contract()
    test_override_group_policy_is_preserved()
    test_multi_source_union_dedupe_and_conflict_report()
    test_multi_source_identical_group_is_deduplicated()
    test_miniprogram_override_alternatives_are_disjunctions()
    test_rule_audit_and_delayed_host_contracts()
    print("splash policy: PASS")
