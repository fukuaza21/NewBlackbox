# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Table of Contents

1. [What this is](#what-this-is)
2. [Repository layout](#repository-layout)
3. [Modules](#modules)
4. [Build system](#build-system)
5. [Build commands](#build-commands)
6. [Signing and release](#signing-and-release)
7. [CI / CD](#ci--cd)
8. [Testing reality](#testing-reality)
9. [Architecture overview — the four layers](#architecture-overview--the-four-layers)
10. [Layer 1: Process model and fake system server](#layer-1-process-model-and-fake-system-server)
11. [Layer 2: Binder proxy layer](#layer-2-binder-proxy-layer)
12. [Layer 3: Hidden-API access (black-reflection)](#layer-3-hidden-api-access-black-reflection)
13. [Layer 4: Native hooks (lib blackbox)](#layer-4-native-hooks-lib-blackbox)
14. [Virtual filesystem layout (BEnvironment)](#virtual-filesystem-layout-benvironment)
15. [Public API surface (BlackBoxCore)](#public-api-surface-blackboxcore)
16. [BActivityThread — per-process heart](#bactivitythread--per-process-heart)
17. [App module (UI shell)](#app-module-ui-shell)
18. [Startup sequence](#startup-sequence)
19. [How to add a new system-service proxy](#how-to-add-a-new-system-service-proxy)
20. [How to add a hidden-API accessor](#how-to-add-a-hidden-api-accessor)
21. [Debugging on device](#debugging-on-device)
22. [Conventions and gotchas](#conventions-and-gotchas)
23. [Crash-hardening philosophy](#crash-hardening-philosophy)
24. [Key dependency map](#key-dependency-map)

---

## What this is

BlackBox is an Android **app-virtualization engine**. It clones and runs APKs inside a
sandboxed virtual environment **without installing them** on the device. Everything
happens in userspace — **no root required**. The project descends from VirtualApp /
VirtualAPK lineage; this fork (maintained by ALEX5402) adds Android 14+ compatibility,
WebView isolation, Google-services faking, WorkManager/JobScheduler compatibility,
device/location spoofing, and pervasive anti-crash hardening.

- Supported Android versions: 5.0 (API 21) through 14+ (compileSdk 35).
- Supported ABIs: `arm64-v8a`, `armeabi-v7a` (x86 mentioned in README but not built by default).
- Host app id: `top.niunaijun.blackbox`; engine namespace: `top.niunaijun.blackbox`.

The important mental model: **a virtual app runs inside a real stub process of the
host APK**, believing it is a normally installed app. Every facility it touches —
package manager, activity manager, files, accounts, location — is intercepted and
answered by BlackBox instead of the real Android system.

---

## Repository layout

```
NewBlackbox/
├── app/                # UI shell (Kotlin) — the launcher app users see
├── Bcore/              # The virtualization engine (Java + C++/NDK)
│   └── src/main/
│       ├── java/top/niunaijun/blackbox/   # engine Java code
│       ├── java/black/                    # generated-style BR* reflection accessors (220 files)
│       ├── cpp/                           # native hooks (ndk-build, Android.mk)
│       ├── aidl/                          # IPC interfaces between processes
│       ├── res/                           # incl. strings.xml with process-name constants
│       └── AndroidManifest.xml            # declares ~355 stub components/processes
├── black-reflection/   # annotation-based hidden-API reflection library (java-library)
├── compiler/           # annotation processor generating BR* bindings (java-library)
├── gradle/libs.versions.toml   # version catalog (app module only)
├── build.gradle        # root: shared ext versions (compileSdk, targetSdk, versionCode…)
├── settings.gradle     # module list + repository lockdown
├── Docs.md             # user-facing API documentation (partially aspirational)
├── README.md           # project intro, build prerequisites
└── .github/workflows/build_and_telegram.yml   # CI: build + Telegram delivery
```

Directories you will open most often:

| Path | What lives there |
|---|---|
| `Bcore/.../blackbox/BlackBoxCore.java` | 2267-line god class: public API + process bootstrap |
| `Bcore/.../blackbox/app/BActivityThread.java` | virtual-app process rebinding & lifecycle |
| `Bcore/.../blackbox/fake/service/` | ~80 `I*Proxy` system-service interceptors |
| `Bcore/.../blackbox/fake/hook/` | hook framework (`HookManager`, `BinderInvocationStub`, `MethodHook`) |
| `Bcore/.../blackbox/fake/frameworks/` | client-side `B*Manager` facades over the fake system server |
| `Bcore/.../blackbox/fake/delegate/` | instrumentation/provider/service-connection delegates |
| `Bcore/.../blackbox/core/system/` | fake system server (ServiceManager, DaemonService, pm/am/user/… services) |
| `Bcore/.../blackbox/core/env/BEnvironment.java` | sandbox filesystem layout definition |
| `Bcore/.../blackbox/proxy/` | stub activities/services/providers declared in the manifest |
| `Bcore/src/main/cpp/` | Dobby-based native hooks, IO redirection, hidden-API bypass |

---

## Modules

### `app/` — UI shell (Kotlin)

- Namespace `top.niunaijun.blackboxa`, applicationId `top.niunaijun.blackbox`.
- Depends on `:Bcore` plus androidx/material/osmdroid (maps for fake location),
  material-dialogs, WorkManager, etc.
- Entry: `app/App.kt` (Application) → `view/main/BlackBoxLoader.kt` attaches BlackBoxCore.
- Screens: `view/main` (home), `view/apps` (virtual app list), `view/list`,
  `view/fake` (fake location picker, osmdroid map), `view/gms` (Google services
  install/state), `view/setting`.
- Data layer: `data/AppsRepository.kt`, `data/FakeLocationRepository.kt`,
  `data/GmsRepository.kt`, `biz/cache`.
- ViewBinding enabled; `minSdk 21`, `targetSdk 28` (see gotcha below).

### `Bcore/` — the engine (Java + C++)

- Android library, namespace `top.niunaijun.blackbox`.
- AIDL enabled (`buildFeatures.aidl = true`) with packaged framework AIDLs
  (`IServiceConnection`, `IAccountManagerResponse`).
- Native via **ndk-build** (`externalNativeBuild.ndkBuild.path = src/main/cpp/Android.mk`)
  — there is **no CMakeLists.txt**; do not add one.
- `consumerProguardFiles consumer-rules.pro` ships keep-rules to dependents.
- Depends on `:black-reflection` (implementation) and `:compiler` (annotationProcessor),
  FreeReflection, toml4j (config files), appcompat/material (legacy).
- `prefab true` — native deps packaged for consumers.

### `black-reflection/` — reflection framework (pure Java)

- `java-library` module (JDK 17), package `top.niunaijun.blackreflection`.
- Provides annotations: `@BClass`, `@BClassName`, `@BMethod`, `@BStaticMethod`,
  `@BField`, `@BStaticField`, `@BConstructor`, `@BParamClass(Name)`, plus
  `*NotProcess` variants controlling code-gen behavior.
- Runtime entry: `BlackReflection` class; utility: `utils/Reflector`.
- You rarely edit this; you consume its generated output (`black.android.*` classes
  inside Bcore).

### `compiler/` — annotation processor (pure Java)

- `java-library` module; generates the `BR*` accessor classes at Bcore compile time
  from black-reflection annotations.
- If `BR*` classes fail to generate, the failure is in this module or in the
  annotation-processing wiring in `Bcore/build.gradle`.

---

## Build system

- **AGP 8.13.2**, **Gradle** wrapper (use `./gradlew`, never system gradle).
- **JDK 21**: `sourceCompatibility`/`targetCompatibility`/`jvmTarget` all 21 in
  `app` and `Bcore`. `black-reflection`/`compiler` use JDK 17. CI uses Temurin 21.
- **compileSdk 35**; **targetSdk 28** (deliberate — see gotchas); **minSdk 21**.
- **NDK exactly `29.0.13846066`** — pinned identically in `app/build.gradle` and
  `Bcore/build.gradle`. A different installed NDK will be auto-downloaded or fail;
  keep both pins in sync if ever changed.
- Kotlin 1.9.23 (app module only).
- Root `build.gradle` `ext` block is the single source for:
  `compileSdkVersion=35`, `targetSdkVersion=28`, `minSdk=21`,
  `versionCode=400`, `versionName="4.0.0"`, plus `xVersion` / `hiddenApiBypass`
  constants.
- **Version catalog** `gradle/libs.versions.toml` is used **only by `app`**
  (appcompat/material/constraintlayout/core-ktx/junit/espresso). Bcore declares
  dependency versions inline. Mixed on purpose — do not "unify" without need.
- **Repository lockdown**: `settings.gradle` sets
  `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)`. Add repositories
  **only** in `settings.gradle` (jitpack, google, mavenCentral are already there);
  a `repositories {}` block in any module build file fails the build.
- jitpack is first in the repo list — several deps (`com.github.*`, `com.gitee.*`)
  resolve only from jitpack.

---

## Build commands

```bash
# Full debug build (app + engine + native)
./gradlew assembleDebug

# Release build (minified, signed per env — see below)
./gradlew assembleRelease

# Exactly what CI runs
./gradlew :app:assembleRelease

# Engine AAR only (for distributing Bcore standalone)
./gradlew :Bcore:assembleRelease

# Clean (native objects too)
./gradlew clean

# Lint (narrowed to NewApi/InlinedApi, abortOnError=false — advisory only)
./gradlew :Bcore:lint

# Unit tests (placeholder-only; see Testing reality)
./gradlew test
./gradlew :Bcore:testDebugUnitTest
```

Output artifacts:

- APKs land in `app/build/outputs/apk/<buildType>/`.
- **ABI splits are enabled** in `app`: separate `armeabi-v7a` and `arm64-v8a` APKs,
  `universalApk false`. Filenames are rewritten to
  `BlackBox_<versionName>_<abi>-<buildType>.apk`
  (e.g. `BlackBox_4.0.0_arm64-v8a-release.apk`).
- `packaging` excludes `**/libandroidx.graphics.path.so` in both `jniLibs` and
  `resources` — duplicate-library conflict workaround; keep it.

First build notes:

- Gradle will want the pinned NDK `29.0.13846066`; let it download or preinstall
  via sdkmanager: `sdkmanager "ndk;29.0.13846066"`.
- jitpack must be reachable for `com.github.tiann:FreeReflection`,
  `com.gitee.cbfg5210:RVAdapter`, etc. `maven.aliyun.com` was deliberately removed
  (commit `db1d1fa`) — do not re-add mirror workarounds into module files.

---

## Signing and release

`app/build.gradle` defines a `release` signingConfig driven by environment variables:

| Env var | Meaning |
|---|---|
| `KEYSTORE_FILE` | path to keystore (used only if the file exists) |
| `KEYSTORE_PASSWORD` | store password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

If `KEYSTORE_FILE` is unset or the path doesn't exist, the build **falls back to
`~/.android/debug.keystore`** with the standard `android`/`androiddebugkey`
credentials — so local "release" builds are silently debug-signed. That's intended
for development; CI supplies real secrets.

Release type: `minifyEnabled true` with `proguard-android-optimize.txt` +
`proguard-rules.pro`. Bcore's `consumer-rules.pro` protects engine internals in
consumer builds.

---

## CI / CD

Single workflow: `.github/workflows/build_and_telegram.yml`
("Build and Send APK to Telegram").

- Triggers: push to `main`, plus `workflow_dispatch`.
- Runner: `ubuntu-latest`; steps: checkout → setup JDK 21 (Temurin, gradle cache) →
  setup Android SDK → accept licenses → decode keystore → `./gradlew :app:assembleRelease`
  → upload each APK to Telegram.
- Keystore arrives as base64 in secrets, decoded to `/tmp/release.keystore`, and the
  four signing env vars are exported for the build (same names as above).
- Required GitHub secrets:
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
  `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`.
- Recent history: workflow was simplified to release-only APK builds with custom
  keystore signing (commit `f05a3be`); earlier it also built and distributed
  `black-reflection`/`compiler` artifacts (`43eb123`).

To reproduce CI locally:

```bash
export KEYSTORE_FILE=/path/to.keystore KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...
./gradlew :app:assembleRelease
ls app/build/outputs/apk/release/
```

---

## Testing reality

There is effectively **no automated test suite**:

- `Bcore` sets `testOptions.unitTests.returnDefaultValues = true` (tests would pass
  vacuously).
- `core/system/JarManagerTest.java` is a placeholder, not a real test.
- `app` has the stock junit/espresso deps but no meaningful tests.
- CI runs no test step.

Verification is **build + run on device/emulator**:

```bash
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/*.apk
adb logcat | grep -iE "blackbox|BlackBoxCore|BActivityThread|HookManager"
```

Treat `Docs.md` "API examples" as documentation of intent; the source of truth is
`BlackBoxCore.java`.

---

## Architecture overview — the four layers

Every engine change lands in one of four cooperating mechanisms. Identify which
layer you're in before editing:

```
┌────────────────────────────────────────────────────────────────┐
│ host UI process (app module)                                   │
│   BlackBoxCore.get() API ────────────────┐                     │
├──────────────────────────────────────────┼─────────────────────┤
│ :black server process                    │  AIDL               │
│   BlackBoxSystem / ServiceManager        ▼                     │
│   BProcessManagerService, B*SystemServices (pm/am/user/…)      │
│   DaemonService (keep-alive)        ▲                          │
├─────────────────────────────────────┼──────────────────────────┤
│ :p0 … :pN  virtual app processes    │                          │
│   BActivityThread rebinds stub → virtual app                   │
│   Layer 2: I*Proxy intercepts system-service binder calls      │
│   Layer 3: BR* reflection + FreeReflection + hidden_api.cpp    │
│   Layer 4: libblackbox native hooks (IO redirect, dex, binder) │
└────────────────────────────────────────────────────────────────┘
```

1. **Process model** — real stub processes (`:p0`…`:pN`, `:black`) declared in
   Bcore's manifest; a fake system server tracks virtual apps/users/processes.
2. **Binder proxy layer** — `I*Proxy` classes replace system-service binders in
   each virtual process and rewrite/fake the calls.
3. **Hidden-API access** — generated `BR*` accessors + FreeReflection + native
   hidden-API bypass let the engine touch framework internals on modern Android.
4. **Native hooks** — Dobby inline hooks redirect the filesystem, hook binder,
   load dex, and neuter runtime checks.

---

## Layer 1: Process model and fake system server

### Process roles

`BlackBoxCore` distinguishes three roles via `isMainProcess()`, `isServerProcess()`,
`isBlackProcess()`:

| Process | Name pattern | Role |
|---|---|---|
| Host UI | host package default | runs the app module UI; talks to `:black` via AIDL |
| Server | `:black` (`@string/black_box_service_name` in Bcore `res/values/strings.xml`) | the fake system server |
| Virtual clients | `:p0`, `:p1`, … `:pN` | one stub process per running virtual app instance |

`proxy/ProxyManifest.java` maps a virtual pid to its process name:
`BlackBoxCore.getHostPkg() + ":p" + bPid`. The manifest declares stub
`ProxyActivity`/`ProxyService`/`ProxyContentProvider`/`ProxyJobService`/
`ProxyVpnService`/`ProxyBroadcastReceiver` families across those processes
(~355 component/process entries in `Bcore/src/main/AndroidManifest.xml`).
When a virtual app starts an activity/service, the intent is rewritten to a stub
component; the stub then hosts the virtual component inside the stub process.

### Fake system server (`core/system/`)

- `BlackBoxSystem` — bootstraps and holds the system-service table.
- `ServiceManager` — registry/lookup for virtual system services; also
  `initBlackManager()` wires the client-side `BlackManager` facade.
- `DaemonService` — keep-alive service in `:black`; `BlackBoxCore.doCreate()`
  contains elaborate retry/alternative-startup logic to bring it up. **That retry
  machinery is deliberate crash hardening — do not strip it during cleanups.**
- `BProcessManagerService` + `ProcessRecord` — tracks which virtual process runs
  which package/userId, handles death and restart.
- `SystemCallProvider` — content-provider entry point the host UI uses to call
  into the server process.
- Virtual system services live in subpackages: `pm/`, `am/`, `user/`, `location/`,
  `accounts/`, `notification/`, `permission/`, `os/`. These are the *virtual*
  PackageManager/ActivityManager/etc. whose state is persisted under
  `BEnvironment.getSystemDir()` (`user.conf`, `accounts.conf`, `uid.conf`,
  `shared-user.conf`, `package.conf` per app, `fake-location.conf`,
  `xposed-module.conf`).
- `JarManager`/`JarConfig` — loads auxiliary dex/jar payloads (e.g. the empty
  dex trick used by `NativeCore.loadEmptyDex()`).

### Startup ordering (why edits here are fragile)

`BlackBoxCore.doAttachBaseContext(context, …)` runs in **every** process of the
host APK (main, `:black`, each `:pN`) and branches by role; `doCreate()` then
finishes initialization. See [Startup sequence](#startup-sequence) below.

---

## Layer 2: Binder proxy layer

### The pattern

~80 classes in `fake/service/`, named `I<Service>Proxy` (e.g.
`IActivityManagerProxy`, `IPackageManagerProxy`, `ILocationManagerProxy`) plus
feature proxies (`GmsProxy`, `GoogleAccountManagerProxy`, `DeviceIdProxy`,
`AntiVirtualDetectProxy`, `AndroidIdProxy`, `BrowserEngineProxy`,
`WorkManagerProxy`, `ContentResolverProxy`, …).

Skeleton (from `ILocationManagerProxy`):

```java
public class ILocationManagerProxy extends BinderInvocationStub {
    public ILocationManagerProxy() {
        super(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }
    @Override protected Object getWho() {
        return BRILocationManagerStub.get().asInterface(
            BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }
    @Override protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.LOCATION_SERVICE);
    }
    @Override public Object invoke(Object proxy, Method method, Object[] args) {
        MethodParameterUtils.replaceFirstAppPkg(args);   // virtual pkg → host pkg
        // …fake or forward…
    }
}
```

Mechanics:

- `BinderInvocationStub` wraps the real service binder with a dynamic-proxy
  invocation handler; `inject()` swaps the proxied binder into the process's
  `ServiceManager` cache so all clients in that process hit the proxy.
- `ClassInvocationStub` is the non-binder sibling for hooking plain classes
  (e.g. `ClassLoaderProxy`, `FileSystemProxy`, `SQLiteDatabaseProxy`).
- Method-level customization uses `@ProxyMethod` annotations and `MethodHook`
  subclasses; `MethodParameterUtils` provides the common argument rewrites
  (`replaceFirstAppPkg`, uid fixes, etc.).
- `fake/hook/HookManager.java` is the registry: a hardcoded `addInjector(new
  …Proxy())` list in `init()`, then `injectAll()`. Registration is
  **conditional on API level** via `BuildCompat.isS()/isR()/isQ()/isPie()/
  isOreo()/isN*/isM()/isL()` — e.g. `IActivityClientProxy`/`IVpnManagerProxy`
  only on S+, `IPermissionManagerProxy` on R+, `IAutofillManagerProxy` on O+.
  Vendor-specific proxies exist (`IXiaomi*Proxy`, `IMiuiSecurityManagerProxy`).
- `HookManager.checkEnv(clazz)` / `checkAll()` re-inject hooks whose environment
  went "bad" (e.g. after the system re-caches binders); `BActivityThread` calls
  `HookManager.get().checkEnv(HCallbackProxy.class)` during binding.
- Hook failures are isolated per-hook (`handleHookError`); "critical" hooks
  (ActivityManager/PackageManager/WebView/ContentProvider) get one automatic
  recovery attempt. `areCriticalHooksInstalled()` and `reinitializeHooks()` are
  the introspection/reset entry points.

### Client-side facades (`fake/frameworks/`)

`BPackageManager`, `BActivityManager`, `BUserManager`, `BLocationManager`,
`BAccountManager`, `BJobManager`, `BNotificationManager`, `BStorageManager`,
`BResourcesManager`, `BlackManager` — thin clients that virtual-process code calls;
they RPC into the `:black` server's matching system service. `BlackBoxCore`
exposes several statically: `getBPackageManager()`, `getBActivityManager()`,
`getBJobManager()`, `getBStorageManager()`.

### Lifecycle glue

- `fake/delegate/AppInstrumentation` (extends `BaseInstrumentationDelegate`) —
  replaces the process Instrumentation to intercept activity/service creation.
- `fake/delegate/ContentProviderDelegate` — installs virtual providers
  (`ContentProviderDelegate.init()` runs in the `:black` process).
- `fake/delegate/ServiceConnectionDelegate`, `InnerReceiverDelegate` — route
  service binds and broadcast deliveries to virtual components.
- `fake/service/HCallbackProxy` — hooks the ActivityThread `H` handler to
  redirect launch messages. Critical to activity startup; checked via
  `checkEnv` on every bind.
- `fake/provider/FileProvider(Handler)` — serves virtual-app files to the host.

---

## Layer 3: Hidden-API access (black-reflection)

Modern Android blocks reflection on non-SDK interfaces. BlackBox needs constant
access to framework internals, via three cooperating mechanisms:

1. **`BR*` accessor classes** — ~220 files under `Bcore/src/main/java/black/`
   in namespaces `black.android.*`, `black.com.*`, `black.dalvik.*`,
   `black.java.*`, `black.libcore.*`. They mirror framework classes
   (`black.android.app.BRActivityThread` ↔ `android.app.ActivityThread`).
   Usage is ordinary static calls:
   `BRActivityThread.get().currentActivityThread()`,
   `BRServiceManager.get().getService(Context.LOCATION_SERVICE)`.
   These are generated/declared through the black-reflection annotation pipeline;
   treat them as read-only generated artifacts. If one is missing a member, the
   fix is to extend the annotated stub, not hand-patch call sites.
2. **FreeReflection** (`com.github.tiann:FreeReflection:3.2.2`) — Java-side
   hidden-API policy bypass; `FakeCore.init()` does
   `ReflectCore.set(android.app.ActivityThread.class)`.
3. **Native bypass** — `cpp/hidden_api.cpp` (`NativeCore.disableHiddenApi()`)
   flips the runtime's hidden-API enforcement flags directly. `NativeCore.init(
   Build.VERSION.SDK_INT)` runs early in every process; **reflection-dependent
   code must run after it**.

`black-reflection` annotations you'll see on stubs: `@BClass`/`@BClassName`
(target framework class), `@BMethod`/`@BStaticMethod`, `@BField`/`@BStaticField`,
`@BConstructor`, `@BParamClass(Name)` for parameter types, and `*NotProcess`
variants that skip processing. `compiler/` generates the glue at build time.

---

## Layer 4: Native hooks (lib blackbox)

`Bcore/src/main/cpp`, built by **ndk-build** (`Android.mk`), producing
`libblackbox.so` for `arm64-v8a` + `armeabi-v7a`.

Third-party native deps:

- **Dobby** — prebuilt static inline-hooking lib, per-ABI archives under
  `cpp/Dobby/<abi>/libdobby.a` (declared as `PREBUILT_STATIC_LIBRARY`).
  Adding a new ABI means dropping in a matching prebuilt.
- **xdl** — compiled from source (`xdl/*.c`), enhanced dlopen/dlsym used to
  resolve un-exported symbols.

Hook sources:

| File | Purpose |
|---|---|
| `BoxCore.cpp/.h` | JNI registration & init entry |
| `IO.cpp/.h` + `Hook/FileSystemHook`, `Hook/UnixFileSystemHook` | path redirection: virtual apps' file ops are rewritten into the sandbox |
| `Hook/BinderHook` | native binder interception |
| `Hook/DexFileHook`, `Hook/VMClassLoaderHook` | make the runtime accept virtual-app dex without installation |
| `Hook/RuntimeHook` | runtime-level tricks |
| `JniHook/JniHook.cpp`, `ArtMethod.h` | JNI/native method hooking |
| `Utils/AntiDetection.cpp`, `VirtualSpoof.cpp` | anti-vm-detection, device spoofing |
| `hidden_api.cpp/.h` | hidden-API enforcement bypass |
| `Log.h`, `Utils/HexDump` | diagnostics |

Java side: `core/NativeCore.java` —

```java
static { System.loadLibrary("blackbox"); }
public static native void init(int apiLevel);
public static native void enableIO();
public static native void addIORule(String targetPath, String relocatePath);
public static native void hideXposed();
public static native boolean disableHiddenApi();
public static native boolean disableResourceLoading();
public static int getCallingUid(int origCallingUid);
public static String redirectPath(String path);
public static File redirectPath(File path);
public static long[] loadEmptyDex();
```

Java-side redirection rules live in `core/IOCore.java`: a singleton holding two
`TrieTree` rule maps plus per-package redirect caches; `addRedirect`,
`addBlackRedirect` (blocked paths), `redirectPath(String/File)`, and
`enableRedirect(Context)` which pushes the rules down into the native layer.
Both Java and native must agree on the sandbox layout — that's
`BEnvironment` (next section).

---

## Virtual filesystem layout (BEnvironment)

`core/env/BEnvironment.java` is the **single definition** of where virtual data
lives. Root: `<host data dir>/blackbox` plus an external mirror under the host's
external files dir. Key paths:

```
blackbox/
├── system/                 # fake-system state
│   ├── user.conf           # virtual users
│   ├── accounts.conf       # virtual accounts
│   ├── uid.conf            # virtual uid allocation
│   ├── shared-user.conf
│   ├── xposed-module.conf
│   └── fake-location.conf
├── cache/                  # JUNIT_JAR, EMPTY_JAR (empty-dex trick payloads)
├── proc/<pid>/             # per-virtual-process scratch
├── data/
│   ├── app/<pkg>/          # installed virtual APKs
│   │   ├── base.apk        # the stored APK
│   │   ├── lib/            # extracted native libs
│   │   └── package.conf    # install metadata
│   ├── user/<userId>/<pkg>/        # virtual app private data (files/, cache/, databases/, lib/, shared_prefs/)
│   └── user_de/<userId>/<pkg>/     # device-encrypted variant
└── (external) storage/emulated/<userId>/Android/{data,obb}/<pkg>/…
```

Notable accessors: `getDataDir(pkg, userId)`, `getDeDataDir`, `getAppDir`,
`getBaseApkDir`, `getAppLibDir`, `getExternalDataDir/ObbDir`,
`getXSharedPreferences(pkg, prefFile)` (used by XSharedPreferences emulation),
`getUserDir(userId)` mirroring Android's `data/user/<id>` semantics.

**Rule:** never hardcode sandbox paths — always go through `BEnvironment`.
IO redirection (Java `IOCore` + native `IO.cpp`) assumes this exact tree.

---

## Public API surface (BlackBoxCore)

`BlackBoxCore.get()` is the entry point for hosts (the app module, or third-party
apps embedding the AAR). Most-used members (see source for full list):

```java
// install / uninstall / launch
InstallResult installPackageAsUser(String packageName, int userId);   // clone installed app
InstallResult installPackageAsUser(File apk, int userId);             // from APK file
InstallResult installPackageAsUser(Uri apk, int userId);              // from content URI
void uninstallPackageAsUser(String packageName, int userId);
void uninstallPackage(String packageName);                            // all users
boolean launchApk(String packageName, int userId);
boolean isInstalled(String packageName, int userId);
List<ApplicationInfo> getInstalledApplications(int flags, int userId);
List<PackageInfo> getInstalledPackages(int flags, int userId);
void clearPackage(String packageName, int userId);
void stopPackage(String packageName, int userId);

// virtual users
List<BUserInfo> getUsers();
BUserInfo createUser(int userId);
void deleteUser(int userId);

// GMS
boolean isSupportGms();
boolean isInstallGms(int userId);   // (+ install/uninstall GMS variants)

// lifecycle hooks for the host
void addAppLifecycleCallback(AppLifecycleCallback cb);
void removeAppLifecycleCallback(AppLifecycleCallback cb);

// misc state
static Context getContext();  static String getHostPkg();
static int getHostUid();      static int getHostUserId();
boolean isSandboxedEnvironment();
boolean areServicesAvailable();
```

`GmsCore` holds the Google-package registry: `GMS_PKG = com.google.android.gms`,
`GSF_PKG = com.google.android.gsf`, `VENDING_PKG = com.android.vending`, plus
known Google app/service sets used to decide special-casing.

Configuration: `BlackBoxCore extends ClientConfiguration`
(`app/configuration/ClientConfiguration`, `AppLifecycleCallback`) — hosts
override/attach behavior there.

---

## BActivityThread — per-process heart

`app/BActivityThread.java` (~1343 lines) runs inside **virtual** processes:

- Singleton: `currentActivityThread()`, guarded `isThreadInit()`.
- Identity of the virtual app in this process: `getAppPackageName()`,
  `getAppProcessName()`, `getAppPid()`, `getBUid()/getBAppId()/getUid()/
  getUserId()/getCallingBUid()`, `getAppConfig()`, `getApplication()`.
- `initProcess(AppConfig)` — called early in a `:pN` process; registers a
  binder-death recipient against the system process, stores config.
- `bindApplication(packageName, processName)` → `handleBindApplication(...)` —
  the heavy lift: loads the virtual APK, creates its `LoadedApk`/Resources/
  ClassLoader, builds a package `Context` (`createPackageContext` with
  `createMinimalPackageContext` / `createWrappedBaseContext` fallbacks — these
  layered fallbacks are crash hardening for hostile apps), installs providers
  (`installProvider`, with `isAntiDetectProvider` filtering), and instantiates
  the virtual `Application` with lifecycle callbacks.
- Component routing: `finishActivity(token)`, `handleNewIntent(token, intent)`,
  `scheduleReceiver(ReceiverData)`, `stopService(intent)`,
  `restartJobService(selfId)`, `getActivityByToken(token)`,
  `ensureActivityContext(activity)`.
- `hookActivityThread()` — installs the thread-level hooks (works with
  `HCallbackProxy`, `AppInstrumentation`); includes `HookManager.get()
  .checkEnv(HCallbackProxy.class)` re-validation.

When a virtual app "doesn't start" / black-screens, the investigation almost
always starts in `handleBindApplication` and the context-creation fallbacks.

---

## App module (UI shell)

Flow: `app/App.kt` (Application) → `app/AppManager.kt` →
`view/main/BlackBoxLoader.kt` — the loader calls `BlackBoxCore.doAttachBaseContext`/
`doCreate` at the right moments and peeks/aborts if the engine is unhealthy.

Screens and their backing repos:

| Screen | Path | Backing repo |
|---|---|---|
| Home / virtual app grid | `view/main`, `view/apps` | `data/AppsRepository.kt` |
| App list (device apps to clone) | `view/list` | `AppsRepository`, `data/AppsSortCompon.kt` |
| Fake location (osmdroid map picker) | `view/fake` | `data/FakeLocationRepository.kt` |
| GMS install/state | `view/gms` | `data/GmsRepository.kt` |
| Settings | `view/setting` | — |

The UI persists fake-location/GMS choices through the repos, which talk to
`BlackBoxCore` API; engine state itself is persisted by the virtual system
services under `BEnvironment.getSystemDir()`.

---

## Startup sequence

What actually happens when the host app cold-starts (all in
`BlackBoxCore.java` unless noted):

1. **Every process** of the host APK runs `doAttachBaseContext(context, …)`:
   - `BEnvironment.load()` creates the sandbox tree.
   - `NativeCore.init(Build.VERSION.SDK_INT)` + hidden-API bypass + FreeReflection
     (`FakeCore.init()`).
   - Role detection (main / server / virtual) decides the rest.
2. `doCreate()`:
   - `installSystemHooks()` — early process-level hooks.
   - `ensureBlackProcessInitialized()` — makes sure `:black` is up; bounded by a
     ~10s soft timeout (`maxInitTime`), then proceeds with fallbacks.
   - Main process: starts/retries `DaemonService` in `:black` with multiple
     alternative startup strategies and delayed rescheduling on failure.
   - `initVpnService()`, then `HookManager.get().init()` → `injectAll()`.
   - `:black` process: `ContentProviderDelegate.init()`.
   - Non-server processes: `ServiceManager.initBlackManager()` (client facade),
     `getBPackageManager().resetTransactionThrottler()`.
   - Any exception triggers a fallback `ServiceManager.initBlackManager()` retry —
     by design the engine degrades rather than crashes the host.
3. Launching a virtual app: host calls `launchApk` → fake `BActivityManager`
   picks/starts a `:pN` stub process → in that process `BActivityThread.
   initProcess` + `bindApplication` loads and runs the virtual app.

When touching startup: preserve ordering (BEnvironment → NativeCore → hooks →
server bring-up) and keep every fallback path.

---

## How to add a new system-service proxy

1. Create `fake/service/IXxxManagerProxy.java` extending `BinderInvocationStub`:
   - constructor: `super(BRServiceManager.get().getService(Context.XXX_SERVICE))`
   - `getWho()`: `BRIXxxStub.get().asInterface(...)` (add a `BR*` accessor first
     if missing — see next section)
   - `inject()`: `replaceSystemService(Context.XXX_SERVICE)`
   - `invoke()` or `@ProxyMethod` handlers: rewrite args with
     `MethodParameterUtils`, forward to a `B*Manager` facade or the real service.
2. Register in `HookManager.init()` — unconditional, or gated with the right
   `BuildCompat.isX()` guard.
3. If the proxy needs server-side state, add/extend the matching virtual service
   under `core/system/` and the client facade under `fake/frameworks/`.
4. Build and verify on device with logcat — no unit tests exist for this layer.

## How to add a hidden-API accessor

1. Find the existing stub under `Bcore/src/main/java/black/...` mirroring your
   framework class (or create one with the same package shape).
2. Annotate members with black-reflection annotations (`@BMethod`, `@BStaticField`,
   …); use `@BParamClassName` when parameter types are themselves hidden.
3. Rebuild — `compiler/` regenerates the `BR*` glue. Call it as
   `BRXxx.get().someMember()`.

---

## Debugging on device

```bash
# broad engine logs
adb logcat | grep -iE "blackbox|BlackBoxCore|BActivityThread|HookManager|NativeCore"

# specific subsystems
adb logcat | grep -E "ILocationManagerProxy|GmsProxy|HCallbackProxy|BProcessManagerService"

# native crashes
adb logcat | grep -iE "DEBUG|tombstone|blackbox"
```

- Tag conventions: each class sets its own `TAG` (usually the class name);
  engine logging goes through `utils/Slog`.
- Crash-capture utilities already exist: `utils/CrashMonitor`,
  `utils/NativeCrashPrevention`, `utils/DexCrashPrevention`,
  `utils/DexFileRecovery`, `utils/LogSender` — check their output/locations
  before adding new crash plumbing.
- Docs.md's troubleshooting section is user-facing; for development, logcat +
  the fallback paths in `BlackBoxCore` are the real tools.

---

## Conventions and gotchas

1. **`targetSdkVersion = 28` is deliberate** (compileSdk 35). Raising targetSdk
   changes platform behaviors the engine relies on (implicit-intent visibility,
   package visibility, background limits, storage). Never bump without auditing
   the proxy layer.
2. **AIDL codegen workaround**: `Bcore/build.gradle` strips the generated
   ` * Using:` comment lines from AIDL output because backslashes in Windows
   paths mis-parse as Unicode escapes during javac. Keep the `doFirst` task when
   touching AIDL or AGP versions.
3. **Repository lockdown**: `FAIL_ON_PROJECT_REPOS` — repositories only in
   `settings.gradle`. Module-level `repositories {}` = build failure.
4. **NDK pin**: `29.0.13846066` in both `app` and `Bcore` — keep in sync.
5. **ndk-build, not CMake**: native config lives in `cpp/Android.mk` +
   `Application.mk`. Dobby is a checked-in per-ABI prebuilt (`cpp/Dobby/`).
6. **`useLegacyPackaging = true`** (Bcore `packagingOptions.jniLibs`) — virtual
   apps load engine `.so` files from extracted paths; modern packaging breaks it.
7. **`libandroidx.graphics.path.so` exclusions** (app `packaging`) — duplicate-lib
   workaround; removing it reintroduces packaging failures.
8. **Java compile tuning** (Bcore): `-Xlint:-deprecation -Xlint:-unchecked
   -Xlint:-rawtypes`, forked javac with `-Xmx2048m`. Large generated `black/`
   tree needs it; don't remove the heap bump.
9. **Lint is advisory**: `checkOnly 'NewApi','InlinedApi'`, `abortOnError false`,
   `checkReleaseBuilds false`. CI lint will not save you.
10. **Two dependency styles**: catalog for `app`, inline versions for `Bcore`.
    Historical; unify only if you're touching both anyway.
11. **`AppConfig` (entity) holds global runtime toggles** — check it before
    inventing new global flags.
12. **Sandbox paths only via `BEnvironment`** — never hardcode `blackbox/` paths.
13. **BR* classes are generated-style artifacts** — extend annotated stubs, don't
    hand-edit call sites to work around missing accessors.
14. **Hooks registration order/guards matter**: keep new injectors behind correct
    `BuildCompat` gates; wrong-gated hooks crash older devices at `injectAll`.
15. **Docs.md is partially aspirational** — when docs and code disagree, the code
    in `BlackBoxCore`/`BActivityThread` wins.

---

## Crash-hardening philosophy

This codebase's defining style: **never let a virtual app kill the host**. You'll
see pervasive try/catch + fallback chains that look over-defensive — they are the
product of real-world hostile/crashy apps:

- `BlackBoxCore.doCreate()` — timeout-bounded init, multi-strategy DaemonService
  startup, delayed rescheduling, fallback `initBlackManager`.
- `HookManager` — per-hook exception isolation, critical-hook recovery attempt,
  `checkEnv`/`checkAll` re-injection, `reinitializeHooks`.
- `BActivityThread` — layered context-creation fallbacks
  (`createPackageContext` → `createMinimalPackageContext` →
  `createWrappedBaseContext`), anti-detect provider filtering.
- `utils/` — `CrashMonitor`, `NativeCrashPrevention`, `DexCrashPrevention`,
  `DexFileRecovery`, `CrashHandler` (core).
- Proxy `invoke()` bodies frequently special-case misbehaving packages (e.g.
  blocking GMS location requests to prevent crashes).

**When refactoring: preserve fallbacks.** Deleting a "redundant" try/catch here
usually reintroduces a field crash. If a fallback is genuinely obsolete, say why
in the commit message.

---

## Key dependency map

| Dependency | Where | Why |
|---|---|---|
| `:Bcore` | app | the engine |
| `:black-reflection` | Bcore | BR* annotation runtime |
| `:compiler` (annotationProcessor) | Bcore | generates BR* glue |
| `com.github.tiann:FreeReflection:3.2.2` | Bcore | hidden-API bypass (Java side) |
| `com.moandjiezana.toml:toml4j:0.7.2` | Bcore | config-file parsing |
| androidx appcompat/material (1.2.0/1.3.0 inline) | Bcore | legacy UI deps inside engine |
| androidx appcompat 1.7.0 / material 1.12.0 / constraintlayout / core-ktx | app (catalog) | UI |
| `org.osmdroid:osmdroid-android:6.1.11` | app | fake-location map |
| `androidx.work:work-runtime:2.7.1` | app | background work |
| `com.afollestad.material-dialogs:{core,input}:3.3.0` | app | dialogs |
| `com.gitee.cbfg5210:RVAdapter`, `com.github.Othershe:CornerLabelView`, `com.github.Ferfalk:SimpleSearchView`, `com.tbuonomo:dotsindicator` | app (jitpack) | UI widgets |
| `androidx.preference:preference-ktx`, lifecycle 2.3.1, recyclerview 1.2.1 | app | screens |
| Dobby (prebuilt `.a`), xdl (source) | Bcore native | inline hooks, symbol resolution |

Runtime artifacts: `LICENSE` (Apache-2.0), `RELEASE_NOTES.md` (per-version
changes), `assets/usage.gif` (readme demo).
