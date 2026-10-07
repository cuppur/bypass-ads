# Bypass Ads UI Information Architecture

This document is the ownership contract for the Bypass Ads product surface.
An interactive capability has exactly one primary entry. Other pages may show
a read-only summary, but do not repeat its operation.

## Root Navigation

The root surface has four peer tabs. Root selection is state in
`MainViewModel.bypassRootTab`, never a navigation-stack entry. All secondary
and retained GKD pages share `MainViewModel.backStack`.

| Root tab | Scope |
| --- | --- |
| Home | Runtime state, master protection, accessibility, notification, battery, background health, summary metrics, and latest result. |
| Ads | Ad categories, splash strategy, application protection control, and rules/subscriptions. |
| Records | Successful skips, suspected failures, response summary, failure detail, retry, teaching, and diagnostics. |
| Settings | Prompts, backup, advanced diagnostics, Full Tools, about, and licenses. |

## Capability Ownership

| Feature | Primary entry | Read-only elsewhere | Implementation |
| --- | --- | --- | --- |
| Master ad protection | Home -> Automatic ad skipping | Ads splash summary | `store.enableMatch` |
| Accessibility service | Home -> Accessibility service switch | Home runtime state | `GkdTileService.setA11yServiceEnabled`, Shizuku, and retained authorization route |
| Notification management | Home -> Notification management | Home protection status | `StatusService` and Android notification settings |
| Battery and background protection | Home -> Battery optimization / Background protection | Home protection status | `fixRestartAutomatorService`, battery settings, and autostart guidance |
| Splash strategy | Ads -> Splash ads -> Splash recognition strategy | Ads splash summary | `BypassAdStrategyMode` (保守/激进/彻底疯狂) + `BypassStrategyPolicy`; runtime gate over Bypass-owned close/X/coordinate candidates; dedicated rules are never gated |
| Generic splash recognition | Ads -> Splash recognition strategy -> Generic splash recognition | None | `GkdBypassEngine.genericFallbackEnabled` |
| Ad-category switches | Ads -> In-app advertising | Ads category counts | Per-group `SubsConfig` |
| Application protection control | Ads -> Application control | Rule coverage list is informational navigation | Bypass subscription `AppConfig` |
| Rule bundle and subscription operations | Ads -> Rules & subscriptions | Ads package summary | `GkdBypassEngine` bundle import/restore and retained subscription tools |
| Advanced rule management | Ads -> Rules & subscriptions -> Advanced rule management | None | Shared detail stack and retained GKD rule routes |
| Records clearing | Records -> Clear records | None | Persistent Bypass ad sessions and blackbox events |
| Failure diagnosis and teaching | Records -> Ad record detail | None | Redacted blackbox reasons, confirmed outcomes and existing GKD matcher |
| Accessibility scope | Settings -> Advanced tools -> Accessibility scope | None | Retained `A11YScopeAppListRoute` |
| Prompt settings | Settings -> Prompt settings | None | `toastWhenClick` |
| Backup | Settings -> Backup and restore | None | `BackupUtils` |
| Diagnostic tools | Settings -> Advanced tools -> Diagnostics | None | Activity/event/raw-action logs, snapshots, crashes, and perf trace |
| Full Tools network functions | Settings -> Full Tools | None | `HttpService` and web/update routes; subscriptions remain under Ads |
| About and licenses | Settings -> About / Licenses | None | Static product pages |

## Duplicate-Entry Gate

The following names have one actionable product entry only: accessibility,
notification management, battery policy, restore bundled rules, subscriptions,
advanced rule management, application control, clear records, and generic
splash recognition. The legacy GKD pages remain reachable only through the
shared secondary stack, not as product root tabs.
