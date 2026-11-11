# Device Toolbox

[中文](README.md) · **English**

An Android device-identity toolbox: rewrite the properties that answer *"what phone is this?"*,
with boot persistence, snapshot rollback and per-app spoofing.

- Package `com.xuzhang.devicetoolbox`
- Min Android 5.0 (API 21), target API 34
- System-wide spoofing needs root: Magisk / KernelSU / APatch (for its `resetprop`)
- Per-app spoofing needs Zygisk (Zygisk Next or the built-in one) — per package, no reboot
- **Only `arm64-v8a` native code is bundled**: per-app spoofing is arm64-only; on other ABIs the
  app still installs, but only the features that don't need the native library work
  (browsing the database, schemes, the generator)
- Fully offline: the device database ships inside the APK, nothing is uploaded

The whole thing — UI, scripts, native module and the device database — is hand-written and
maintained by the author. Route references are credited at the end.

---

## Features

### What gets rewritten

Not a handful of loose keys — a **self-consistent identity**:

- Six partitions (system / vendor / odm / product / system_ext / bootimage) are aligned together:
  model, brand, manufacturer, device, codename, product name
- Build fingerprint, build id, Android release, security patch and incremental are rewritten in step,
  so *About phone*, `getprop` and `android.os.Build` agree with each other
- Keys with no real value for the target device are **left alone** — better to skip than to invent
- The confirmation dialog reports how many keys will actually be written; that number is computed per
  target device, not a hard-coded marketing figure

### Device database

**3,539 built-in entries**, **3,312 of them (93.6%) with real fingerprints**, **278 brands**,
about **572 KB** of data, covering Android 5.0 (API 21) through Android 17.

- Every entry is a complete identity: brand / manufacturer / marketing name / model / codename /
  product / release / build id / incremental / fingerprint / security patch / chipset / platform
- Search by marketing name, model code or chipset; filter by brand and by Android version
  (all / 10+ / 12+); pin favourites
- Custom device generator: given brand, model, chipset and version it derives codename, build id,
  security patch and a full fingerprint

Data is merged and de-duplicated from public sources (see [THIRD-PARTY.md](THIRD-PARTY.md)).
Sources disagree with each other, so on conflict the entry with more fields **and** a real
fingerprint wins; when a fingerprint doesn't match the entry's brand / codename / release,
the field stays empty rather than carrying a fabricated value.

### Safety rails

- **Restore from factory truth, not from memory**: truth can only come from the read-only
  `build.prop` files on `/system`, `/vendor`, `/odm` (which `resetprop` cannot touch). A baseline is
  taken on first use and every restore reads from it
- **Snapshot before writing**: the pre-change values of every key about to be touched are written to
  `restore.sh`, plus a `values.tsv` expectation list
- **Read-back verification after writing**, and after restoring — a mismatch is reported as
  "did not take effect", never faked as success
- **Properties that never existed are deleted, not set to empty**: `resetprop --delete`, because an
  empty shell property is still detectable
- Restores only touch keys this app changed; "factory reset" style operations need two confirmations

### Per-app spoofing (Zygisk)

Applies only to apps you tick. The module works in the **specialize stage** of the target process,
before its code runs, and patches both paths at once:

- **Java side**: replace the static fields of `Build` / `Build.VERSION`
- **Native side**: clone a private copy of the property area inside that process, point bionic's
  property lookup at the copy (copy-on-write), then write values using bionic's own protocol

So `SystemProperties` and `__system_property_find` return the same values — no two-truths mismatch
inside one process. **System properties are untouched (the rest of the device keeps its real
identity), and changing the configuration needs no reboot.**

Matching is by **exact package name**, not prefix: `com.tencent.mobileqq2` is never caught by a
`com.tencent.mobileqq` rule.

Configuration is written by this app into a root-only module directory; the target process has no
root and cannot read it, so it asks the root-side module process over Zygisk's companion socket
(deletions travel on a second round trip of the same connection, so a delete marker never appears
in the value domain).

> An earlier Xposed path is **retired**: it only patched the Java layer, which native code reading
> the property area directly bypasses, and it threw `AbstractMethodError` under LSPosed on real
> devices. The manifest no longer registers any Xposed module.

### Everything else

- Persistent boot module: installs into the root manager's module directory, writes early at boot
- Schemes and share codes (`DTB1.xxxx.yyyy`, CRC-checked) to reproduce a configuration elsewhere
- Three-way consistency self-check: `getprop` / `android.os.Build` / the intended values, side by side
- Light and dark themes, switchable without restart
- Export a standalone script to `/sdcard/ModelSpoof` that runs without the app

---

## Build

Both halves build **on the device itself**; no PC required.

```
app/      Android app (Java, built with aapt2 + javac + d8 + apksigner)
module/   Zygisk native module (C++)
```

Order matters: **module first, then the app** — the app embeds the module's own `.so` as its
native self-check library, so both paths run the same code.

### 1. Native module

```bash
cd module
bash build.sh
```

Produces `module/pkg/zygisk/libdtbprobe.so` (the same binary also kept as `arm64-v8a.so` for
Zygisk's naming convention) and refreshes `module/pkg/module.prop`.

Needs a clang that accepts `-target aarch64-linux-android24` — the NDK's clang or any cross
compiler will do. The `.so` deliberately does not link `libc++` (only `libc` / `liblog`), so Zygisk
can `dlopen` it from anywhere.

### 2. App

```bash
cd app
bash build.sh
```

Steps: `aapt2 compile` → `aapt2 link` → `javac` → `d8` → append `classes.dex` and
`lib/arm64-v8a/libdtbprobe.so` into the APK → `apksigner sign`.

Three things are **not** in the repo and must be provided:

- **`android.jar`** (~26 MB) at `app/tools/android.jar`, or via `ANDROID_JAR`
- **`d8.jar` and `apksigner.jar`** from Android SDK build-tools, via `D8` and `APKSIGNER`
  (no defaults on purpose — the script fails loudly instead of guessing)
- **A signing keystore**, via `KEYSTORE`, `KS_PASS`, `KS_ALIAS` (`KEY_PASS` if the key password differs)

Both scripts locate themselves with `cd "$(dirname "$0")"` and hard-code no absolute paths; every
overridable environment variable is documented at the top of each script.

### CI

`.github/workflows/build.yml` reproduces the same build on Ubuntu: SDK + NDK installed via
`sdkmanager`, module compiled with the NDK clang, APK built with build-tools and signed with a
throwaway CI key, then `apksigner verify` and an APK content assertion. Push a `v*` tag and the APK
plus a flashable `DeviceToolbox-zygisk-<version>.zip` are attached to the Release.
`.github/workflows/ci.yml` runs the device-database validators and script syntax checks on every
push and pull request.

---

## Source layout

```
app/
├── AndroidManifest.xml
├── build.sh                     on-device build script
├── assets/devices.tsv           3,539-entry device database (13 tab-separated columns)
├── res/                         vector icons, colours, strings
├── tools/android.jar            bring your own, not committed
├── tools-src/                   check-fingerprints.py, check_config.py (offline validators)
└── java/com/xuzhang/devicetoolbox/
    ├── MainActivity.java        app bar + bottom navigation + page host
    ├── core/                    Sh, Runner, Device, Target, Props, Scripts, Library,
    │                            Engine, Zygisk, AppSpoof, Store, Share
    └── ui/                      Palette, Ui, Page, HomePage, SpoofPage, ModelPage,
                                 SchemePage, ToolsPage, Task

module/
├── build.sh                     on-device build script
├── jni/
│   ├── spoof.cpp                Zygisk entry + Build fields + property area
│   ├── prop_area.hpp            bionic property area read/write
│   └── zygisk.hpp               Zygisk module API header (topjohnwu, see credits)
└── pkg/                         packaged: module.prop + zygisk/*.so
```

---

## Why "consistency" is the hard part

After you rewrite properties, plenty of detectors still win — and the reason is **read timing**:

> `Build.MODEL` / `Build.FINGERPRINT` are Java **static fields**, frozen when the process starts.
> A runtime `resetprop` only touches the in-memory property area, which **affects processes started
> afterwards**. In an already-running app `Build.MODEL` is still the old value while
> `SystemProperties.get()` already returns the new one — two sources in one process, and any
> detector that compares them wins.

To be truly consistent, properties must be written **before the app starts**:

| Path | Scope | Notes |
|---|---|---|
| **Boot module** (post-fs-data) | Whole device | Written before zygote, so every process sees the new identity; one reboot makes it permanent |
| **Per-app spoofing** (Zygisk) | Ticked apps only | Patched in the specialize stage, before the app's code runs, so the app is internally consistent |

Runtime `resetprop` is fine for a quick look, but do not expect device-wide consistency — the UI
says so explicitly.

---

## Usage notes

1. **Grant root first.** Magisk / KernelSU prompt on the first `su` call; if the manager isn't
   running the request hangs and the home screen shows "not granted" — open the manager once.
   The raw `su` output is visible on the home screen to make this debuggable.
2. **Per-app spoofing needs the Zygisk module installed** (reboot once so zygote loads it; after
   that, configuration changes need no reboot). **LSPosed is not required** — this app registers no
   Xposed module.
3. **Rewriting properties carries risk.** Some apps validate the device identity and may misbehave.
   "Restore original" rolls back.
4. Restores use snapshots. With no successful snapshot yet, "Restore original" says so instead of
   writing something arbitrary.
5. After uninstalling the persistent module, a **reboot** is required; already-applied properties do
   not revert immediately.

---

## License

**GNU General Public License v3.0 (GPL-3.0)** — full text in [LICENSE](LICENSE).

Copyright (C) 2026 xuzhang-android

You may use, modify and redistribute this project; derivative works must be open-sourced under the
same license, keeping the copyright and license notices.

Changing system properties on a real device is risky. Evaluate it yourself; you are responsible for
the consequences of using this tool.

---

## Credits

- `module/jni/zygisk.hpp` is by **John "topjohnwu" Wu** (author of Magisk), published under a
  permissive 0BSD/ISC-style license and used verbatim.
- The **Magisk / KernelSU / APatch / Zygisk Next** ecosystem: system-wide writes depend on their
  `resetprop`, per-app spoofing depends on the Zygisk module API.
- Public data sources for the device database:
  - [`xdaGari/tadiphone-buildprop-archive`](https://github.com/xdaGari/tadiphone-buildprop-archive)
    — 44,716 partition-level `build.prop` files; the largest single source
  - [`TheFreeman193/PIFS`](https://github.com/TheFreeman193/PIFS) — real fingerprint archive
  - [`Seyud/device_faker_config`](https://github.com/Seyud/device_faker_config) — public device
    templates including `marketname`
  - Public firmware archives (e.g. Samsung PDA codes on samfrew / sammobile) for build ids and
    release info
- [`Seyud/device_faker`](https://github.com/Seyud/device_faker) — public description of the
  "Zygisk per-app + Java static fields together with the property area" route, which pointed us in
  the right direction. The implementation here is written from scratch; the only third-party file
  is `module/jni/zygisk.hpp`.
- And everyone who keeps those public archives alive.
