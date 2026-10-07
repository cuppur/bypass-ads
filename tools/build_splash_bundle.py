#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build the Bypass Ads bundled advertising subscription.

Pipeline (single source of truth for the self-use rule stack):

    third-party advertising rules (开屏/全屏/局部/分段广告)
            ↓ filter
    merge rules/bypass_overrides.json   (Bypass-owned overrides, tracked in git)
            ↓
    append host rules (WeChat / Alipay miniprogram host patches)
            ↓
    retain mature splash global groups, or append a conservative fallback
            ↓
    validate + report
            ↓
    app/src/main/assets/bypass_splash_rules.local.json (gitignored)

License note: the generated file is written to a gitignored path and the
third-party rule bodies are NEVER committed. The repository only tracks the
generator, the validator, the small self-owned fixture
(app/src/main/assets/bypass_splash_rules.json) and rules/bypass_overrides.json.

Usage:
    python tools/build_splash_bundle.py <primary.json5> [output.json] [--additional secondary.json5]
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

SPLASH_GROUP_PREFIX = "开屏广告"
FULLSCREEN_GROUP_PREFIX = "全屏广告"
LOCAL_GROUP_PREFIX = "局部广告"
SEGMENT_GROUP_PREFIX = "分段广告"
AD_GROUP_PREFIXES = (
    SPLASH_GROUP_PREFIX,
    FULLSCREEN_GROUP_PREFIX,
    LOCAL_GROUP_PREFIX,
    SEGMENT_GROUP_PREFIX,
)
REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUTPUT = REPO_ROOT / "app/src/main/assets/bypass_splash_rules.local.json"
OVERRIDES_PATH = REPO_ROOT / "rules/bypass_overrides.json"
CATEGORY_MAP_PATH = REPO_ROOT / "rules/category_map.json"
CONFLICT_REPORT_DEFAULT = REPO_ROOT / "build/bypass-rule-conflicts.json"

# ---------------------------------------------------------------------------
# Conservative generic splash fallback (global group). The mature source
# global rule is preferred. This group is emitted only when the source has no
# splash global rule, so broad global selectors never compete with each other.
# The last rule uses clickCenter only for a short, visible, skip-semantic node
# while explicit exits remain event-driven. Risky app classes are blocked.
# ---------------------------------------------------------------------------
GENERIC_FALLBACK_GROUP = {
    "key": 9000,
    "name": "开屏广告-通用跳过",
    "forcedTime": 10000,
    "actionMaximum": 1,
    "resetMatch": "app",
    "fastQuery": True,
    "rules": [
        {
            "key": 0,
            "anyMatches": [
                '[clickable=true][visibleToUser=true][width<500 && height<300][(text.length<10 && (text*="跳过" || text*="跳 过" || text*="跳過" || text~="(?is).*skip.*")) || (desc.length<10 && (desc*="跳过" || desc*="跳過" || desc~="(?is).*skip.*")) || (vid~="(?is).*skip.*" && vid!~="(?is).*video.*" && vid!~="(?is).*head.*" && vid!~="(?is).*tail.*") || id$="tt_splash_skip_btn"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]',
                '@[clickable=true][visibleToUser=true][width<500 && height<300] > [childCount=0][visibleToUser=true][(text.length<10 && (text*="跳过" || text*="跳 过" || text*="跳過" || text~="(?is).*skip.*")) || (desc.length<10 && (desc*="跳过" || desc*="跳過" || desc~="(?is).*skip.*")) || (vid~="(?is).*skip.*" && vid!~="(?is).*video.*" && vid!~="(?is).*head.*" && vid!~="(?is).*tail.*") || id$="tt_splash_skip_btn"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
            ],
        },
        {
            "key": 1,
            "action": "clickCenter",
            "anyMatches": [
                '[clickable=false][childCount=0][visibleToUser=true][width<300 && height<200][(text.length<10 && (text*="跳过" || text*="跳 过" || text*="跳過" || text~="(?is).*skip.*")) || (desc.length<10 && (desc*="跳过" || desc*="跳過" || desc~="(?is).*skip.*")) || (vid~="(?is).*skip.*" && vid!~="(?is).*video.*" && vid!~="(?is).*head.*" && vid!~="(?is).*tail.*") || id$="tt_splash_skip_btn"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
            ]
        },
    ],
    # enable=false -> these apps are excluded from the generic fallback.
    # Hosts with their own precise rules plus high-risk categories.
    "apps": [
        {"id": "com.tencent.mm", "enable": False},
        {"id": "com.eg.android.AlipayGphone", "enable": False},
        # payment / banking
        {"id": "com.unionpay", "enable": False},
        {"id": "com.unionpay.cloudpay", "enable": False},
        {"id": "com.icbc", "enable": False},
        {"id": "com.ccb.life", "enable": False},
        {"id": "com.cmbchina.ccd.pluto.cmbActivity", "enable": False},
        {"id": "cmb.pb", "enable": False},
        {"id": "com.android.bankabc", "enable": False},
        {"id": "com.boc.bocrm", "enable": False},
        {"id": "com.bankcomm.Bankcomm", "enable": False},
        {"id": "com.psbc.mobilebank", "enable": False},
        {"id": "com.spdbccc.app", "enable": False},
        {"id": "com.cib.android", "enable": False},
        {"id": "com.ecitic.bank.mobile", "enable": False},
        {"id": "com.cebbank.mobile.cemb", "enable": False},
        {"id": "com.pingan.paces.ccms", "enable": False},
        {"id": "com.cgbchina.xpt", "enable": False},
        {"id": "com.hxb.creditcard", "enable": False},
        {"id": "com.jd.jrapp", "enable": False},
        {"id": "com.jdjr", "enable": False},
        {"id": "com.duxiaoman.jinrong", "enable": False},
        {"id": "com.lu.com", "enable": False},
        # securities / crypto
        {"id": "com.hexin.plat.android", "enable": False},
        {"id": "com.eastmoney.android.fund", "enable": False},
        {"id": "com.xueqiu.android", "enable": False},
        {"id": "com.futu.mobile.tiger", "enable": False},
        {"id": "com.tigerbrokers.stock", "enable": False},
        {"id": "com.binance.dev", "enable": False},
        {"id": "com.okinc.okex", "enable": False},
        # authenticators / password managers
        {"id": "com.google.android.apps.authenticator2", "enable": False},
        {"id": "com.azure.authenticator", "enable": False},
        {"id": "com.lastpass.lpandroid", "enable": False},
        {"id": "com.1password.1password", "enable": False},
        # GKD already excludes system apps by default. These explicit entries
        # also cover OEM system surfaces that are not marked as system apps.
        {"id": "com.android.systemui", "enable": False},
        {"id": "com.android.settings", "enable": False},
        {"id": "com.google.android.permissioncontroller", "enable": False},
        {"id": "com.android.permissioncontroller", "enable": False},
        {"id": "com.google.android.packageinstaller", "enable": False},
        {"id": "com.android.packageinstaller", "enable": False},
        {"id": "com.miui.securitycenter", "enable": False},
        {"id": "com.miui.home", "enable": False},
        {"id": "com.huawei.systemmanager", "enable": False},
        {"id": "com.coloros.safecenter", "enable": False},
        {"id": "com.vivo.permissionmanager", "enable": False},
        # Bypass Ads itself
        {"id": "app.bypassads", "enable": False},
        {"id": "app.bypassads.debug", "enable": False},
    ],
}

SAFETY_EXCLUSIONS_PATH = Path(__file__).resolve().parent.parent / "rules" / "safety_exclusions.json"
ASSETS_SAFETY_EXCLUSIONS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "bypass_safety_exclusions.json"


def load_safety_exclusions() -> dict:
    """Single source of truth for safety exclusions (rules/safety_exclusions.json)."""
    if not SAFETY_EXCLUSIONS_PATH.exists():
        print("WARNING: rules/safety_exclusions.json not found, using built-in list", file=sys.stderr)
        return {
            "generator_disabled_apps": [item["id"] for item in GENERIC_FALLBACK_GROUP.get("apps", [])],
        }
    return json.loads(SAFETY_EXCLUSIONS_PATH.read_text(encoding="utf-8"))


# The runtime veto list and the generator disabled list live in the JSON; the
# literal above remains as a fallback when the file is missing.
GENERIC_FALLBACK_GROUP["apps"] = [
    {"id": app_id, "enable": False}
    for app_id in load_safety_exclusions().get("generator_disabled_apps", [])
]

# Source-global rules remain the primary broad matcher. When present, these
# Bypass-owned rules are appended to that same mature group rather than
# creating a competing second global group. They cover two interaction shapes
# that the current source group can identify but cannot safely actuate:
# a bounded clickable parent and a small non-clickable skip target.
SOURCE_GLOBAL_REINFORCEMENT_RULES = (
    {
        "name": "Bypass Ads 明确跳过补强",
        "matches": GENERIC_FALLBACK_GROUP["rules"][0]["anyMatches"][0].replace("width<500", "width<420").replace("height<300", "height<260"),
    },
    {
        "name": "Bypass Ads 可点击父节点补强",
        "anyMatches": [
            '@[clickable=true][visibleToUser=true][width<500 && height<300] > [childCount=0][visibleToUser=true][(text.length<10 && (text*="跳过" || text*="跳 过" || text*="跳過" || text~="(?is).*skip.*")) || (desc.length<10 && (desc*="跳过" || desc*="跳過" || desc~="(?is).*skip.*")) || (vid~="(?is).*skip.*" && vid!~="(?is).*video.*" && vid!~="(?is).*head.*" && vid!~="(?is).*tail.*") || id$="tt_splash_skip_btn"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
        ],
    },
    {
        "name": "Bypass Ads 安全手势补强",
        "action": "clickCenter",
        "anyMatches": [
            '[clickable=false][childCount=0][visibleToUser=true][width<300 && height<200][(text.length<10 && (text*="跳过" || text*="跳 过" || text*="跳過" || text~="(?is).*skip.*")) || (desc.length<10 && (desc*="跳过" || desc*="跳過" || desc~="(?is).*skip.*")) || (vid~="(?is).*skip.*" && vid!~="(?is).*video.*" && vid!~="(?is).*head.*" && vid!~="(?is).*tail.*") || id$="tt_splash_skip_btn"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
        ],
    },
    {
        # Strategy-gated close text / desc / view id. The runtime gate
        # (BypassStrategyGate) decides whether this rule may run: it only
        # actuates in AGGRESSIVE+ modes. bypassMode is the structured
        # security metadata; the name marker is only for log readability.
        "name": "Bypass Ads 策略化关闭补强-Aggressive",
        "bypassMode": "AGGRESSIVE",
        "anyMatches": [
            '[clickable=true][visibleToUser=true][width<300 && height<200][(text="关闭" || text="关闭广告" || text="关闭此广告" || text="关闭该广告" || text="關閉" || text="關閉廣告" || text="Close" || text="close")][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]',
            '[clickable=true][visibleToUser=true][width<300 && height<200][(desc="关闭" || desc="关闭广告" || desc="關閉" || desc="close" || desc="Close")][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]',
            '[clickable=true][visibleToUser=true][width<300 && height<200][vid~="(?is).*(ad_close|splash_close|close_ad|close_btn|close_icon)"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
        ],
    },
    {
        # Strategy-gated structural X / × glyph. Only actuates in AGGRESSIVE+
        # modes, and only for a small, visible glyph. The runtime gate also
        # enforces the ad-context window.
        "name": "Bypass Ads 策略化 X 补强-Aggressive",
        "bypassMode": "AGGRESSIVE",
        "action": "clickCenter",
        "anyMatches": [
            '[clickable=true][visibleToUser=true][width<120 && height<120][(text="X" || text="×" || text="✕")][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]',
            '[clickable=false][childCount=0][visibleToUser=true][width<120 && height<120][(text="X" || text="×" || text="✕")][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
        ],
    },
    {
        # CRAZY-only structural close: a small text-less ImageView/View
        # inside a strong ad context. This is what makes CRAZY different
        # from AGGRESSIVE in candidate discovery.
        "name": "Bypass Ads 策略化结构关闭补强-Crazy",
        "bypassMode": "CRAZY",
        "action": "clickCenter",
        "anyMatches": [
            '[clickable=true][visibleToUser=true][width<160 && height<160][name="android.view.View" || name="android.widget.ImageView"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]',
            '[clickable=false][childCount=0][visibleToUser=true][width<160 && height<160][name="android.view.View" || name="android.widget.ImageView"][text!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"][desc!~="(?is).*(next|下一步|完成|设置|搜索|历史记录|阅读并同意|跳过片头|跳过片尾|跳过视频|取消|退出|帮助).*"]'
        ],
    },
)


def reinforce_source_global_groups(groups: list) -> int:
    """Add Bypass-owned safe actuation to one retained source-global group.

    The primary mature group stays responsible for broad matching. The two
    additions only cover interaction shapes that need a different target or
    action; marker names make the merge idempotent across repeated builds.
    """
    if not groups:
        return 0
    target = next((g for g in groups if g.get("name") == "开屏广告-全局"), groups[0])
    rules = target.setdefault("rules", [])
    existing_names = {rule.get("name") for rule in rules}
    next_key = max((rule.get("key", -1) for rule in rules if isinstance(rule.get("key", -1), int)), default=-1) + 1
    added = 0
    for template in SOURCE_GLOBAL_REINFORCEMENT_RULES:
        if template["name"] in existing_names:
            continue
        rules.append({**template, "key": next_key})
        existing_names.add(template["name"])
        next_key += 1
        added += 1
    return added


def strip_json5(text: str) -> str:
    """Minimal JSON5 -> JSON cleanup: strip // and /* */ comments, trailing
    commas, and convert single-quoted strings to double-quoted ones (handles
    \\\\ and \\' escapes inside single-quoted strings)."""
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':
            # copy double-quoted string verbatim
            out.append(c)
            i += 1
            while i < n:
                out.append(text[i])
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i + 1])
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            # convert single-quoted string to double-quoted
            out.append('"')
            i += 1
            while i < n:
                ch = text[i]
                if ch == "\\" and i + 1 < n:
                    nxt = text[i + 1]
                    if nxt == "'":
                        out.append("\\'")
                    else:
                        out.append("\\")
                        out.append(nxt)
                    i += 2
                    continue
                if ch == "'":
                    out.append('"')
                    i += 1
                    break
                out.append(ch)
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    cleaned = "".join(out)
    # remove trailing commas before } or ]
    cleaned = re.sub(r",\s*([}\]])", r"\1", cleaned)
    return cleaned


def is_splash_group(name: str) -> bool:
    return name == SPLASH_GROUP_PREFIX or name.startswith(SPLASH_GROUP_PREFIX + "-")


def is_ad_group(name: str) -> bool:
    return any(name == prefix or name.startswith(prefix + "-") for prefix in AD_GROUP_PREFIXES)


def is_splash_global_group(name: str) -> bool:
    return is_splash_group(name)


def product_categories(source_categories: list) -> list:
    """Keep stable GKD category keys while setting Bypass product defaults."""
    defaults = {
        SPLASH_GROUP_PREFIX: True,
        FULLSCREEN_GROUP_PREFIX: True,
        LOCAL_GROUP_PREFIX: False,
        SEGMENT_GROUP_PREFIX: False,
    }
    categories = []
    for category in source_categories:
        name = category.get("name", "")
        if name in defaults:
            categories.append({**category, "enable": defaults[name]})
    return categories


def load_category_map() -> dict:
    """Load the tracked category policy used by both the generator audit and
    the Android product facade. Keeping this checked here catches accidental
    drift between the build-time policy and the packaged asset."""
    if not CATEGORY_MAP_PATH.exists():
        raise ValueError(f"missing category map: {CATEGORY_MAP_PATH}")
    category_map = json.loads(CATEGORY_MAP_PATH.read_text(encoding="utf-8"))
    valid = {"SPLASH", "IN_APP_FULLSCREEN", "MARKETING_POPUP", "OTHER_CLOSABLE"}
    invalid = {
        key: value for key, value in category_map.get("overrides", {}).items()
        if value not in valid
    }
    if invalid:
        raise ValueError(f"invalid category override values: {invalid}")
    return category_map


def classify_product_category(app_id: str, group_name: str, category_map: dict) -> str:
    override = category_map.get("overrides", {}).get(f"{app_id}/{group_name}")
    if override:
        return override
    if any(word and word.lower() in group_name.lower() for word in category_map.get("marketingKeywords", [])):
        return "MARKETING_POPUP"
    if is_splash_group(group_name):
        return "SPLASH"
    if group_name == FULLSCREEN_GROUP_PREFIX or group_name.startswith(FULLSCREEN_GROUP_PREFIX + "-"):
        return "IN_APP_FULLSCREEN"
    return "OTHER_CLOSABLE"


def validate_category_map(bundle: dict, category_map: dict) -> list:
    """Ensure explicit overrides address real advertising groups and report a
    deterministic category distribution for the build audit."""
    available = {
        f"{app.get('id')}/{group.get('name')}"
        for app in bundle.get("apps", [])
        for group in app.get("groups", [])
    }
    errors = [f"分类覆盖不存在: {key}" for key in category_map.get("overrides", {}) if key not in available]
    return errors


def load_subscription(raw: str):
    """Parse a GKD subscription that may be JSON or JSON5. Prefers the json5
    library when installed; falls back to the built-in minimal cleaner."""
    try:
        import json5  # type: ignore
        return json5.loads(raw)
    except Exception:
        pass
    return json.loads(strip_json5(raw))


def source_provenance(data: dict, path: Path) -> dict:
    return {
        "sourceName": data.get("name") or path.stem,
        "sourceVersion": data.get("version"),
        "sourceSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
    }


def normalized_group(group: dict) -> str:
    """Compare content without generated keys/provenance.

    A same-named group with different selectors is deliberately a conflict,
    not an invitation to concatenate rule bodies. The primary source wins and
    the report gives a human reviewer the evidence needed to choose later.
    """
    body = {k: v for k, v in group.items() if k not in {"key", "bypassProvenance"}}
    return json.dumps(body, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalize_rules(value) -> list:
    """GKD accepts a group-level string/object as a single rule, too."""
    rules = value if isinstance(value, list) else [value]
    return [{"matches": rule} if isinstance(rule, list) else rule for rule in rules]


def prepare_source(data: dict, provenance: dict) -> tuple[list, list]:
    apps = []
    for app in data.get("apps", []):
        groups = []
        for group in app.get("groups", []):
            if is_ad_group(group.get("name", "")):
                prepared = {**group, "rules": normalize_rules(group.get("rules", [])), "bypassProvenance": [provenance]}
                if app.get("id") in {"com.tencent.mm", "com.eg.android.AlipayGphone"} and "小程序" in group.get("name", ""):
                    prepared["matchRoot"] = True
                    # SDK layouts are shared by base XRiverActivity and its
                    # $Main/$1 subclasses. Keep the existing prefix and add
                    # the base activity for this ad-only mini-program group.
                    scopes = prepared.get("activityIds", [])
                    scopes = [scopes] if isinstance(scopes, str) else list(scopes)
                    base = "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity"
                    if app.get("id") == "com.eg.android.AlipayGphone" and base + "$" in scopes:
                        prepared["activityIds"] = list(dict.fromkeys(scopes + [base]))
                groups.append(prepared)
        if groups:
            apps.append({**app, "groups": groups})
    globals_ = [
        {**group, "rules": normalize_rules(group.get("rules", [])), "bypassProvenance": [provenance]}
        for group in data.get("globalGroups", [])
        if is_splash_global_group(group.get("name", ""))
    ]
    return apps, globals_


def merge_sources(sources: list[tuple[dict, Path]]) -> tuple[dict, dict]:
    """Merge coverage by source priority without merging incompatible groups.

    The first tuple is primary, later tuples are secondary. Bypass-owned
    overrides are still applied after this function, therefore outrank every
    third-party source. Only group identity and provenance leave this builder;
    no third-party rule body is placed in the report.
    """
    app_map: dict[str, dict] = {}
    app_group_content: dict[tuple[str, str], str] = {}
    global_map: dict[str, dict] = {}
    global_content: dict[str, str] = {}
    source_app_sets: list[set[str]] = []
    source_splash_groups: list[set[str]] = []
    conflicts = []
    duplicates = 0

    for source_index, (data, path) in enumerate(sources):
        provenance = source_provenance(data, path)
        apps, globals_ = prepare_source(data, provenance)
        source_app_sets.append({app["id"] for app in apps})
        source_splash_groups.append({group.get("name", "") for group in globals_})
        for app in apps:
            target = app_map.setdefault(
                app["id"],
                {key: value for key, value in app.items() if key != "groups"} | {"groups": []},
            )
            for group in app["groups"]:
                group_key = (app["id"], group.get("name", ""))
                content = normalized_group(group)
                previous = app_group_content.get(group_key)
                if previous is None:
                    target["groups"].append(group)
                    app_group_content[group_key] = content
                elif previous == content:
                    duplicates += 1
                else:
                    conflicts.append({
                        "kind": "appGroup",
                        "appId": app["id"],
                        "groupName": group.get("name", ""),
                        "keptSource": sources[0][1].name if source_index else path.name,
                        "rejectedSource": path.name,
                    })
        for group in globals_:
            name = group.get("name", "")
            content = normalized_group(group)
            previous = global_content.get(name)
            if previous is None:
                global_map[name] = group
                global_content[name] = content
            elif previous == content:
                duplicates += 1
            else:
                conflicts.append({
                    "kind": "globalGroup",
                    "groupName": name,
                    "keptSource": sources[0][1].name if source_index else path.name,
                    "rejectedSource": path.name,
                })

    primary_apps = source_app_sets[0] if source_app_sets else set()
    secondary_apps = set().union(*source_app_sets[1:]) if len(source_app_sets) > 1 else set()
    primary_splash = source_splash_groups[0] if source_splash_groups else set()
    secondary_splash = set().union(*source_splash_groups[1:]) if len(source_splash_groups) > 1 else set()
    report = {
        "sources": [source_provenance(data, path) for data, path in sources],
        "coverageDiff": {
            "primaryOnlyApps": sorted(primary_apps - secondary_apps),
            "secondaryOnlyApps": sorted(secondary_apps - primary_apps),
            "sharedApps": sorted(primary_apps & secondary_apps),
            "uniquePrimarySplashGroups": sorted(primary_splash - secondary_splash),
            "uniqueSecondarySplashGroups": sorted(secondary_splash - primary_splash),
        },
        "conflicts": conflicts,
        "deduplicatedGroups": duplicates,
    }
    return {
        "apps": list(app_map.values()),
        "globalGroups": list(global_map.values()),
        "primary": sources[0][0],
    }, report


def _same_rule(a: dict, b: dict | str) -> bool:
    """Rules are duplicates only when matching, action and policy semantics
    are identical. Names and assigned keys do not affect execution."""
    if isinstance(b, str):
        b = {"matches": b}
    def semantics(rule: dict) -> dict:
        body = {key: value for key, value in rule.items() if key not in {"key", "name"}}
        for field in ("matches", "anyMatches", "excludeMatches", "excludeAllMatches"):
            if isinstance(body.get(field), str):
                body[field] = [body[field]]
        return body
    return semantics(a) == semantics(b)


def apply_overrides(apps: list, overrides: dict) -> int:
    """Merge Bypass-owned overrides into the filtered app list by
    (appId + group name). Idempotent: an override rule whose content already
    exists in the group is not appended twice. Returns the number of rules
    actually appended."""
    added = 0
    for ov_app in overrides.get("apps", []):
        app = next((a for a in apps if a.get("id") == ov_app.get("id")), None)
        if app is None:
            app = {"id": ov_app["id"], "name": ov_app.get("name", ov_app["id"]), "groups": []}
            apps.append(app)
        for ov_group in ov_app.get("groups", []):
            group = next((g for g in app["groups"] if g.get("name") == ov_group.get("name")), None)
            if group is None:
                # Override groups can carry matching-policy fields such as
                # ignoreGlobalGroupMatch. Keep those fields when creating a
                # new group; otherwise a source global rule can accidentally
                # suppress the deterministic override's companion coverage.
                group = {
                    key: value
                    for key, value in ov_group.items()
                    if key not in {"key", "rules"}
                }
                group["key"] = max((g.get("key", 0) for g in app["groups"]), default=-1) + 1
                group.setdefault("forcedTime", 10000)
                group["rules"] = []
                group["bypassProvenance"] = [{
                    "sourceName": "Bypass override",
                    "sourceVersion": 1,
                    "sourceSha256": sha256_of(OVERRIDES_PATH),
                }]
                app["groups"].append(group)
            existing = group.get("rules", [])
            for rule in ov_group.get("rules", []):
                new_rule = dict(rule)
                # Same-name upstream groups can have an older host allowlist.
                # Carry our scope on our rules, without widening source rules.
                for scope_field in ("activityIds", "excludeActivityIds"):
                    if scope_field not in new_rule and scope_field in ov_group:
                        new_rule[scope_field] = ov_group[scope_field]
                if any(_same_rule(new_rule, r) for r in existing):
                    continue
                # never collide with existing keys; keep override keys stable
                used_keys = {r.get("key", -1) for r in existing if isinstance(r, dict)}
                key = rule.get("key")
                while key is None or key in used_keys:
                    key = max(used_keys, default=-1) + 1
                new_rule["key"] = key
                existing.append(new_rule)
                provenance = group.setdefault("bypassProvenance", [])
                override_source = {
                    "sourceName": "Bypass override",
                    "sourceVersion": 1,
                    "sourceSha256": sha256_of(OVERRIDES_PATH),
                }
                if override_source not in provenance:
                    provenance.append(override_source)
                used_keys.add(key)
                added += 1
    return added


def add_generic_fallback(bundle: dict) -> int:
    """Append the generic splash fallback global group (idempotent by name).
    Returns 1 when appended, 0 when already present."""
    groups = bundle.setdefault("globalGroups", [])
    if any(g.get("name") == GENERIC_FALLBACK_GROUP["name"] for g in groups):
        return 0
    groups.append(GENERIC_FALLBACK_GROUP)
    return 1


def validate_rule_contracts(bundle: dict) -> list:
    """Audit every rule, including GKD's string shorthand and unkeyed rules."""
    errors = []
    scopes = [(a.get("id"), a.get("groups", [])) for a in bundle.get("apps", [])]
    scopes.append(("global", bundle.get("globalGroups", [])))
    for scope, groups in scopes:
        group_keys = set()
        for group in groups:
            key = group.get("key")
            if key in group_keys:
                errors.append(f"重复组编号: {scope}/{key}")
            group_keys.add(key)
            rule_keys = set()
            for index, raw in enumerate(normalize_rules(group.get("rules", []))):
                rule = {"matches": raw} if isinstance(raw, str) else raw
                if not isinstance(rule, dict):
                    errors.append(f"非法规则: {scope}/{key}/{index}")
                    continue
                rkey = rule.get("key")
                if rkey is not None:
                    if rkey in rule_keys:
                        errors.append(f"重复规则编号: {scope}/{key}/{rkey}")
                    rule_keys.add(rkey)
                if not (rule.get("matches") or rule.get("anyMatches")):
                    errors.append(f"无匹配条件: {scope}/{key}/{index}")
                if rule.get("bypassOrigin") == "OVERRIDE" and rule.get("bypassMode") not in (None, "AGGRESSIVE", "CRAZY"):
                    errors.append(f"非法策略: {scope}/{key}/{index}")
    return errors


def validate_bundle(bundle: dict) -> list:
    """Validate the final bundle. Returns a list of error strings (empty = ok)."""
    errors = validate_rule_contracts(bundle)
    # 1. only product advertising groups are allowed
    for app in bundle.get("apps", []):
        for g in app.get("groups", []):
            if not is_ad_group(g.get("name", "")):
                errors.append(f"非广告组: {app.get('id')}/{g.get('name')}")
    # 2. globalGroups: splash groups only; fallback is used only if absent.
    for g in bundle.get("globalGroups", []):
        if not is_splash_global_group(g.get("name", "")):
            errors.append(f"非法的全局组: {g.get('name')}")
    source_global = [g for g in bundle.get("globalGroups", []) if g.get("name") != GENERIC_FALLBACK_GROUP["name"]]
    fallback = [g for g in bundle.get("globalGroups", []) if g.get("name") == GENERIC_FALLBACK_GROUP["name"]]
    if source_global and fallback:
        errors.append("成熟全局开屏规则与 Bypass 兜底规则同时存在")
    if not source_global and len(fallback) != 1:
        errors.append("缺少通用开屏规则")
    # 3. presence checks
    app_ids = {a.get("id") for a in bundle.get("apps", [])}
    if "com.tencent.qqmusic" not in app_ids:
        errors.append("缺少 QQ音乐")
    if "com.tencent.mm" not in app_ids:
        errors.append("缺少 微信")
    if "com.eg.android.AlipayGphone" not in app_ids:
        errors.append("缺少 支付宝")
    # 4. QQ音乐 must keep a clickCenter-capable splash rule
    qq = next((a for a in bundle.get("apps", []) if a.get("id") == "com.tencent.qqmusic"), None)
    if qq:
        has_click = any(
            r.get("action") == "clickCenter" or r.get("position") is not None
            for g in qq.get("groups", [])
            for r in g.get("rules", [])
        )
        if not has_click:
            errors.append("QQ音乐缺少 clickCenter 规则")
    # 5. host miniprogram groups
    wx = next((a for a in bundle.get("apps", []) if a.get("id") == "com.tencent.mm"), None)
    if wx and not any(g.get("name") == "开屏广告-微信小程序" for g in wx.get("groups", [])):
        errors.append("微信缺少 开屏广告-微信小程序 组")
    ali = next((a for a in bundle.get("apps", []) if a.get("id") == "com.eg.android.AlipayGphone"), None)
    if ali and not any(g.get("name") == "开屏广告-小程序开屏广告" for g in ali.get("groups", [])):
        errors.append("支付宝缺少 开屏广告-小程序开屏广告 组")
    return errors


def summarize(bundle: dict) -> dict:
    apps = bundle.get("apps", [])
    groups = sum(len(a.get("groups", [])) for a in apps) + len(bundle.get("globalGroups", []))
    rules = sum(
        len(g.get("rules", []))
        for a in apps
        for g in a.get("groups", [])
    ) + sum(
        len(g.get("rules", []))
        for g in bundle.get("globalGroups", [])
    )
    return {"apps": len(apps), "groups": groups, "rules": rules}


def sha256_of(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description="Build a local Bypass Ads advertising bundle")
    parser.add_argument("subscription_path", nargs="?", help="Primary JSON/JSON5 subscription")
    parser.add_argument("output", nargs="?", default=str(DEFAULT_OUTPUT), help="Generated local bundle path")
    parser.add_argument(
        "--additional",
        action="append",
        default=[],
        metavar="PATH",
        help="Secondary JSON/JSON5 subscription; repeat for more sources",
    )
    parser.add_argument(
        "--conflict-report",
        default=str(CONFLICT_REPORT_DEFAULT),
        metavar="PATH",
        help="Local report path containing coverage diff and conflicts only",
    )
    args = parser.parse_args()
    if not args.subscription_path:
        parser.print_help()
        return 2
    source_paths = [Path(args.subscription_path), *(Path(value) for value in args.additional)]
    dst = Path(args.output)
    parsed_sources = []
    for source_path in source_paths:
        if not source_path.exists():
            print(f"ERROR: input subscription not found: {source_path}", file=sys.stderr)
            return 1
        try:
            parsed_sources.append((load_subscription(source_path.read_text(encoding="utf-8")), source_path))
        except Exception as e:
            print(f"ERROR: failed to parse {source_path}: {e}", file=sys.stderr)
            print("Note: strings must use double quotes; comments/trailing commas are OK.", file=sys.stderr)
            return 1
    try:
        category_map = load_category_map()
    except Exception as e:
        print(f"ERROR: failed to load category map: {e}", file=sys.stderr)
        return 1

    merged, report = merge_sources(parsed_sources)
    data = merged["primary"]
    apps_in = data.get("apps", [])
    kept_apps = merged["apps"]
    kept_groups = sum(len(app["groups"]) for app in kept_apps)
    kept_rules = sum(len(group.get("rules", [])) for app in kept_apps for group in app["groups"])

    # Bypass-owned overrides (tracked in git)
    overrides_added = 0
    if OVERRIDES_PATH.exists():
        try:
            overrides = json.loads(OVERRIDES_PATH.read_text(encoding="utf-8"))
            overrides_added = apply_overrides(kept_apps, overrides)
        except Exception as e:
            print(f"ERROR: failed to load overrides {OVERRIDES_PATH}: {e}", file=sys.stderr)
            return 1
    else:
        print("WARNING: rules/bypass_overrides.json not found, skip overrides")

    bundle = {
        "id": data.get("id", 100000001),
        "name": "Bypass Ads 广告规则",
        "version": data.get("version", 1),
        "author": data.get("author", "bypass-ads"),
        "globalGroups": merged["globalGroups"],
        "categories": product_categories(data.get("categories", [])),
        "apps": kept_apps,
        "bypassSourcePriority": [
            "Bypass own override",
            "primary subscription",
            "secondary subscription",
            "source global",
            "Bypass fallback",
        ],
    }
    source_globals_kept = len(bundle["globalGroups"])
    source_global_reinforcements = reinforce_source_global_groups(bundle["globalGroups"])
    fallback_added = 0 if source_globals_kept else add_generic_fallback(bundle)

    errors = validate_bundle(bundle) + validate_category_map(bundle, category_map)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(bundle, ensure_ascii=False, indent=2), encoding="utf-8")
    # Ship the single-source safety exclusions to the app assets so the
    # Kotlin runtime veto list and the generator list never drift.
    if SAFETY_EXCLUSIONS_PATH.exists():
        ASSETS_SAFETY_EXCLUSIONS.parent.mkdir(parents=True, exist_ok=True)
        import shutil as _shutil
        _shutil.copyfile(SAFETY_EXCLUSIONS_PATH, ASSETS_SAFETY_EXCLUSIONS)
    report_path = Path(args.conflict_report)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    summary = summarize(bundle)
    print(f"primary subscription: {source_paths[0]}")
    for index, path in enumerate(source_paths[1:], start=1):
        print(f"secondary source {index}: {path}")
    print(f"  total apps       : {len(apps_in)}")
    print("filtered advertising groups:")
    print(f"  kept apps        : {len(kept_apps)}")
    print(f"  kept groups      : {kept_groups}")
    print(f"  kept rules       : {kept_rules}")
    print(f"overrides added    : {overrides_added}")
    source_splash_globals = [g for g in merged["globalGroups"] if is_splash_global_group(g.get("name", ""))]
    print(f"source global groups input: {len(source_splash_globals)}")
    print(f"source global groups kept : {source_globals_kept}")
    print(f"source global reinforcements: {source_global_reinforcements}")
    print(f"generic fallback          : {'added' if fallback_added else 'not used'}")
    diff = report["coverageDiff"]
    print(f"coverage A-only/shared/B-only apps: {len(diff['primaryOnlyApps'])}/{len(diff['sharedApps'])}/{len(diff['secondaryOnlyApps'])}")
    print(f"source conflicts           : {len(report['conflicts'])} (report: {report_path})")
    print(f"deduplicated groups        : {report['deduplicatedGroups']}")
    print(f"final apps/groups/rules: {summary['apps']}/{summary['groups']}/{summary['rules']}")
    category_counts = {}
    for app_rule in kept_apps:
        for group in app_rule["groups"]:
            category = classify_product_category(app_rule["id"], group["name"], category_map)
            category_counts[category] = category_counts.get(category, 0) + 1
    print("product category groups: " + ", ".join(
        f"{category}={category_counts.get(category, 0)}"
        for category in ("SPLASH", "IN_APP_FULLSCREEN", "MARKETING_POPUP", "OTHER_CLOSABLE")
    ))
    for want in ("com.tencent.qqmusic", "com.tencent.mm", "com.eg.android.AlipayGphone"):
        hit = next((a for a in kept_apps if a.get("id") == want), None)
        if hit:
            names = [g.get("name") for g in hit["groups"]]
            print(f"  {want:32s} IN BUNDLE  groups={names}")
        else:
            print(f"  {want:32s} absent")
    if errors:
        print("VALIDATION FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"validation          : PASS")
    print(f"output             : {dst}")
    print(f"output sha256      : {sha256_of(dst)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
