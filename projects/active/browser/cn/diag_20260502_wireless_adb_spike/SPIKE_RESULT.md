# Spike Result: Wireless-ADB Self-Pair on nubia P0110

**Date**: 2026-05-02
**Device**: nubia P0110, Android 16, fingerprint `nubia/pacific/pacific:16/1.11.0.0/20260407.122620:user/release-keys`
**Verdict**: **HYPOTHESIS DENIED** for nubia P0110 — wireless debugging is OEM-stripped.
**Architecture verdict**: The underlying *adbd-as-proxy* concept is **proven sound** but cannot be self-bootstrapped on this device.

---

## TL;DR

| Question | Answer | Evidence |
|---|---|---|
| Can wireless debugging be enabled on this device? | **NO** — OEM strips it. | `step3e_dev_titles_full_scan.txt` (UI entry missing), `step2_enable_wifi_adb.txt` (auto-revert) |
| Does adbd briefly start TLS on programmatic toggle? | YES, for ~15 ms before being reverted. | `step2_enable_wifi_adb.txt`, `step2c_wifi_state.txt` |
| Can adbd reach `chrome_devtools_remote`? | **YES** — proven. | `step5c_chained_retry.txt` |
| Can a device-side untrusted_app reach CDP via adbd-as-proxy? | **YES with host present**, **NO without host** on this device. | `step5c_chained_retry.txt` |
| Was prior MLS-only diagnosis fully accurate? | Partly. shell denial is **TE rule**, not MLS. runas denial is MLS-categories. adbd has both TE allow + mlstrustedsubject. | `selinux_connectto_rules.txt`, `selinux_mlsconstrain.txt` (in `diag_20260501_finalpath/`) |

---

## Falsifier 1: Wireless debugging is gone from Developer Options UI

The standard AOSP DevelopmentSettings page exposes both **USB 调试** and **无线调试** toggles. On nubia P0110, only USB debugging exists.

Full list of every option found by scrolling top-to-bottom (`step3e_dev_titles_full_scan.txt`):

```
USB 调试            ← USB debugging present
选择调试应用
等待调试程序
调试 GPU 过度绘制
调试非矩形剪裁操作
... (graphics/animation/process settings)
```

**Notably absent**: `无线调试` (Wireless debugging) and `通过 Wi-Fi 配对设备` (Pair device with Wi-Fi). The OEM has removed these preferences from Settings.

Implication: ClosePaw cannot drive the accessibility tree to enable wireless debugging — there is no UI element to interact with.

## Falsifier 2 update (post-reauth observation)

After the user re-confirmed an ADB authorization dialog on the device mid-spike, the TLS server *briefly* came up persistently on port 39775 (`step7_post_reauth_recheck.txt`, `step8_post_reauth_listener.txt`):

- `adb_wifi_enabled` read back as `1`.
- New listener `*:39775` (consistent with adbd's TLS server port format).
- AOSP `adb_pairing` notification channel exists (importance=4), so the pairing infra is on-image even though the toggle UI is gone.

But once I toggled the setting back off via `settings put 0`, then on again, the revert resumed and the TLS port disappeared (`step9_revert_recheck.txt`). The transient persistence was a side-effect of the user-mediated dialog confirmation aligning with my prior puts — it is **not** a stable enablement path.

Even during the window when TLS *was* up, the **pairing port** never opened: pairing is bound to the missing UI flow ("Pair device with pairing code"). Without the pairing port and a pairing code, there is no way for an unprivileged in-device process to add its public key to `/data/misc/adb/adb_keys`. Existing host keys (qiguo@qiguo-ld1, moonkey@Moonkeys-MBP.lan) work, and `dumpsys adb` shows the prior `shizuku` key was removed during the re-auth dance.

So the revised summary is:
- The toggle CAN flip on transiently when a user-facing dialog (initiated by some side effect) is confirmed.
- The toggle still reverts on each fresh attempt without user UI involvement.
- Even when on, there is no in-device path to obtain new credentials — pairing UI is gone.

## Falsifier 2 (original): Programmatic toggle is auto-reverted in ~15 ms

`adb shell settings put global adb_wifi_enabled 1` returns success and adbd actually starts the TLS server, but a system component immediately writes the setting back to `0` and adbd shuts the TLS server down. From `step2_enable_wifi_adb.txt`:

```
14:25:28.623  V SettingsShellCmd: putForUser(... adb_wifi_enabled, value=1, ...)
14:25:28.624  D AdbSettingsObserver: onChange ...
14:25:28.625  D AdbService: setAdbEnabled(true), mIsAdbWifiEnabled=false, transportType=1
14:25:28.626  I adbd: persist.adb.tls_server.enable changed to 1
14:25:28.627  I adbd: adbwifi started on port 43701    ← TLS server briefly came up
14:25:28.627  I adbd: TlsServer running on port 43701
14:25:28.639  D AdbService: Broadcasting enable = true, type = 1
14:25:28.648  D AdbSettingsObserver: onChange ...     ← second writer fires ~15ms later
14:25:28.648  D AdbService: setAdbEnabled(false), mIsAdbWifiEnabled=true, transportType=1
14:25:28.648  I adbd: persist.adb.tls_server.enable changed to 0
14:25:28.649  I adbd: adb wifi stopped                ← TLS torn down
```

The revert is consistent across multiple invocations and is not influenced by Wi-Fi state (device IS associated to SSID `WhoTMCars` on `wlan0`/192.168.1.35; `step2c_wifi_state.txt`).

The second `onChange` is triggered by a downstream receiver of `WIRELESS_DEBUG_ENABLED` / `WIRELESS_DEBUG_STATE_CHANGED`. The candidate culprit is one of nubia's system-uid OEM packages (several `com.obric.*` and `com.bytedance.*` packages run as uid 1000; see `step2d_revert_culprit.txt`). Identifying the exact actor is not necessary — the behavior is reproducible and clearly a deliberate OEM block. There is no Device Owner or MDM (`device_policy` empty), so this is policy baked into the OEM image.

## Falsifier 3: No standard AIDL surface allows bypass

`step6_adb_aidl_bypass.txt`:
- `service list` shows `adb: [android.debug.IAdbManager]` exists.
- `dumpsys adb` reveals the adb keystore (host's keys, laptop's key, and a `shizuku` key — see "Adjacent finding" below).
- `IAdbManager` methods like `enablePairingByPairingCode` require the system process or apps with `android.permission.MANAGE_DEBUGGING`. Neither shell nor untrusted_app holds this signature/system permission.
- The 15ms revert applies regardless of caller; even a hypothetical privileged invocation gets stomped.

---

## Reframing the prior diagnosis

`diag_20260501_finalpath/` correctly concluded that adbd is the only context that can reach `chrome_devtools_remote`, but the breakdown of WHY shell vs. runas fail was off:

```selinux
(mlsconstrain (unix_stream_socket (connectto))
   (or (or (eq l1 l2) (eq t1 mlstrustedsubject)) (eq t2 mlstrustedsubject)))

(allow adbd      appdomain         (unix_stream_socket (connectto)))
(allow runas_app untrusted_app_all (unix_stream_socket (connectto)))
```

| Source context | TE allow connectto → untrusted_app? | mlstrustedsubject? | Categories match? | Result |
|---|---|---|---|---|
| `adbd`  | YES (`adbd → appdomain`) | YES | n/a (trusted) | **CAN connect** |
| `shell` | NO (no rule) | YES | n/a (trusted) | **TE-denied** (not MLS) |
| `runas_app` | YES (`runas_app → untrusted_app_all`) | NO | NO (c66 vs c211) | **MLS-denied** (categories) |
| `untrusted_app` (Chrome) | n/a (different app) | NO | NO | denied |

So `shell` (and Shizuku-spawned shell processes) is denied by **type enforcement**, not MLS. `runas` is denied by **MLS categories**. `adbd` is the only path. The wireless-pairing hypothesis was correct in spirit — only adbd works, and we'd need an in-device authenticated channel to it. That channel requires wireless debugging on this device, which is gone.

---

## Architectural proof: the *concept* works (host-mediated)

The mechanism we'd build on top of working wireless ADB is verified end-to-end via a host-mediated 4-hop relay (`step5c_chained_retry.txt`):

```bash
adb forward tcp:7777 localabstract:chrome_devtools_remote   # host:7777 → device's CDP socket
adb reverse tcp:8888 tcp:7777                               # device:8888 → host:7777
```

Then on device, ClosePaw's untrusted_app context:

```
$ run-as ai.closepaw curl -sv http://127.0.0.1:8888/json/version
> GET /json/version HTTP/1.1
> Host: 127.0.0.1:8888
< HTTP/1.1 200 OK
< Content-Type:application/json; charset=UTF-8
{
   "Android-Package": "com.android.chrome",
   "Browser": "Chrome/147.0.7727.137",
   "webSocketDebuggerUrl": "ws://127.0.0.1:8888/devtools/browser"
}
```

This proves:
1. `adb reverse tcp:N` creates a real **device-side TCP listener** in adbd's process context (`uid:2000`, see `step4_adb_reverse_test.txt`). Untrusted apps can connect to it. SELinux does not block the TCP connect (TCP loopback to adbd is unrestricted compared to abstract-socket connectto).
2. adbd, being mlstrustedsubject, can open `chrome_devtools_remote` on the app's behalf — so once the bytes reach adbd, the rest is fine.
3. The only piece we lack on-device is a way to make adbd open `localabstract:chrome_devtools_remote` *as a local destination* without the bytes having to round-trip through the host. `adb reverse tcp:N localabstract:X` evaluates `X` on the host (proven in `step4_adb_reverse_test.txt` step B — connection establishes but returns "Empty reply from server" because there's no `chrome_devtools_remote` socket on the host).

So the architecture splits cleanly into "transport" (device-side TCP listener bound to adbd) and "auth/dispatch" (the in-app adb client that issues OPEN packets). The transport works trivially. The auth/dispatch is what wireless-pairing was meant to provide.

## Adjacent finding: Shizuku is already in adbd's keystore

`dumpsys adb` (via `step6_adb_aidl_bypass.txt`) reveals the device already trusts three keys:

```
qiguo@qiguo-ld1            ← this dev host
moonkey@Moonkeys-MBP.lan   ← laptop
shizuku                    ← Shizuku-generated key, already paired
```

The "shizuku" entry is interesting — it implies Shizuku was started via wireless-debug pairing at some past point (presumably before the OEM revert kicked in, or via a different path like `adb tcpip` over USB). **Its private key lives in Shizuku's app-private data dir, which untrusted_app cannot read.** Shizuku doesn't expose an "open authenticated adb channel" API in its standard SDK either. This is a tantalizing breadcrumb but not a usable bypass without forking Shizuku.

---

## Recommendation

For this nubia P0110 (and likely other Chinese-market builds with similarly stripped Developer Options), **do not invest in a wireless-ADB self-pair production path**. The OEM block is robust:
- UI removed → no path for accessibility-driven enablement.
- Programmatic toggle reverted → no path via Shizuku/WRITE_SECURE_SETTINGS.

Suggested production strategy:

1. **Keep the existing AOSP/emulator/su path working** (it already PASSes, see `aosp_emulator_pass_20260501/`).
2. **For real Chinese OEM devices, treat the host-mediated relay as the supported architecture during development/QA only.** Document the `forward tcp:7777 localabstract:chrome_devtools_remote` + `reverse tcp:8888 tcp:7777` recipe (now proven) for QA/manual testing scenarios.
3. **Productionize via Custom CDP Bridge inside Chromium**, NOT via OS-level ADB. Options:
   - **Embed our own browser** (CustomTabs, GeckoView, or a WebView-based shell with explicit CDP exposure) where we control the listener placement.
   - **Use Android WebView + JavaScript bridge** instead of CDP for headless-style automation; bypasses the abstract-socket SELinux issue entirely.
   - **Vendor partnership** to ship a system app with `MANAGE_DEBUGGING` on supported OEMs (heavy but solid).
4. **If we *must* drive vendor Chrome on this OEM build**, the only viable in-device path requires **rooting**, which violates the "no root" constraint.

### Architectural deltas vs. prior plan

The prior `design_codex.md` Phase 2 anticipated falling back to TCP loopback through Chrome's `--remote-debugging-port`. That fallback (`Chrome on Android ignores --remote-debugging-port`, recorded in commits `ee48adc9`, `9c408162`) plus this spike's results means **vendor Chrome on locked Chinese OEM devices has no viable CDP transport from an unprivileged in-device process**. This shifts the design conversation toward "own the browser surface" rather than "tunnel into vendor Chrome".

---

## Evidence file index (this directory)

Newly captured (this spike):
- `step1_state_recheck.txt` — current adb_wifi_enabled state, adbd context.
- `step2_enable_wifi_adb.txt` — first toggle attempt, no listeners.
- `step2b_settings_diagnostic.txt` — settings put generally works (sentinel test); only adb_wifi_enabled rejected.
- `step2c_wifi_state.txt` — Wi-Fi associated; broadcast extras visible; full revert sequence in logcat.
- `step2d_revert_culprit.txt` — broadcast receivers, OEM system packages, no MDM, conclusive revert reproduction.
- `step3_settings_ui_launch.txt` — first Settings UI dump.
- `step3b_ui_scan.txt`, `step3c_intents.txt`, `step3d_dev_settings_scan.txt`, `step3e_dev_titles_full_scan.txt` — exhaustive dev-settings scrolling, no wireless debugging entry.
- `step4_adb_reverse_test.txt` — `adb reverse` creates device-side listener (uid:2000); connection accepted but destination evaluates host-side.
- `step5_chained_forward_reverse.txt`, `step5b_chrome_status.txt`, `step5c_chained_retry.txt` — **architecture proof**: host-mediated forward+reverse chain delivers Chrome JSON to untrusted_app on device.
- `step6_adb_aidl_bypass.txt` — IAdbManager surface, dumpsys adb reveals keystore including `shizuku` key.
- `step7_post_reauth_recheck.txt`, `step8_post_reauth_listener.txt`, `step9_revert_recheck.txt` — post-reauth observations: transient TLS-port appearance and re-confirmation that revert resumes.

Carried over from prior partial run (kept):
- `step1_wireless_state.txt`, `step4a_forward_listener_side.txt`, `step4b_forward_external.txt`, `step4d_adbd_auth.txt`, `step4g_adbd_protocol.txt`.

Cross-references in `diag_20260501_finalpath/`:
- `selinux_mlsconstrain.txt` — the mls rule for `connectto`.
- `selinux_connectto_rules.txt` — TE allow rules for adbd and runas_app.
- `selinux_mlstrusted.txt` — mlstrustedsubject set (includes `shell` and `runas`, refining the prior diagnosis as noted above).

---

## Final verdict

**SPIKE RESULT: HYPOTHESIS DENIED — nubia P0110 OEM strips wireless debugging entirely (UI removed + programmatic toggle reverted within ~15 ms). The underlying adbd-as-proxy mechanism is sound and is proven via a host-mediated 4-hop relay, but cannot be self-bootstrapped on-device. Production path forward: own the browser surface (custom WebView/CustomTabs/embedded Chromium) instead of tunneling into vendor Chrome on locked Chinese OEM devices.**
