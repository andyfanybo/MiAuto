# ICCOA CarLink 集成说明

MiAuto 以 [DiAuto Pro](https://github.com/zutaozhong-hash/DiAuto-Pro) 为基座重建，把手机侧协议
从 **Android Auto** 换成 **ICCOA CarLink**（接住小米 CarWith、OPPO Car+ 车联、vivo Jovi InCar
等走同一套协议的手机端）。本文记录这次替换中真正踩过的坑，
供后续维护者参考。

---

## 1. 为什么以 DiAuto 为基座，而不是从极简工程派生

上一版 MiAuto 派生自最小验证工程 —— 只有 4 个类、1 个布局，**根本没有设置页**。
于是 DiAuto 里那套「常规 + 高级」设置（含 `仪表投屏` / `投屏时机` / `HUD 未识别不显示`）
在 MiAuto 里全都对不上。

改为以 DiAuto 为基座后，这些设置**原样回归**。

## 2. 现在的 MiAuto 由哪些层组成

| 层 | 来源 | 说明 |
|---|---|---|
| UI 外壳 | DiAuto | `MainActivity` + Navigation（首页 / 菜单 / 启动屏 / 各设置子页） |
| 设置体系 | DiAuto | `main/SettingsActivity.kt` / `SettingsFragment.kt`：常规 · 无线连接 · 深色模式 · 自动化 · 导航 · 图形 · 视频 · 输入 · 音频 · 备份 · 重置 · 调试 · 信息 |
| BYD 仪表 + HUD 输出 | DiAuto | `hud/` + `cluster/`；`BydNavigationOutputs.available()` 就是「HUD 未识别则不显示开关」的门控 |
| 投影渲染 | DiAuto | `view/`（SurfaceView / TextureView / GLES）+ `aap/AapProjectionActivity` |
| 手机协议 | **ICCOA（新）** | `connection/IcocaBridge.kt` + `com.miauto.app.IccoaManager` + `app/libs/icar-sdk.dex` |

> 内部 Java 包名仍为 `com.andrerinas.openheadunit`（沿用基座，避免 275 个源文件 + JNI/proto/AIDL
> 的批量改名风险）；**对外的 `applicationId` 是 `com.miauto.app`**，与 DiAuto
> （`com.andrerinas.headunitrevived`）独立共存。

## 3. 移植 SDK 时必须一起搬的三样东西

只把 SDK 的 dex 放进工程**不等于** SDK 可用。真机上「手机完全扫不到车机、界面无配对码、
点任何按钮都无反应」的根因，就是**只搬了 dex**。dex 只等于 SDK 的**类**，另外三份必须一起搬：

| 组成 | 漏掉时的症状 | MiAuto 的做法 |
|---|---|---|
| **`<service>` 组件** | 系统起不了 SDK 的 Service → **静默失效，零报错** | `AndroidManifest.xml` 显式注册 `com.share.connect.ShareLinkService` / `.ble.BluetoothLeService` / `.wifiap.WifiApService` / `.wifip2p.WifiP2pService` + `androidx.room.MultiInstanceInvalidationService` |
| **`<uses-permission>`** | 权限不足 → API 静默失败 | 逐条对齐样本：`TETHER_PRIVILEGED` / `OVERRIDE_WIFI_CONFIG` / `NETWORK_SETTINGS` / `NETWORK_STACK` / `LOCAL_MAC_ADDRESS` / `QUERY_ALL_PACKAGES` / `MANAGE_USB` 等 |
| **Gradle 传递依赖** | `NoClassDefFoundError` | `implementation("androidx.room:room-runtime:2.4.3")` —— SDK 用 Room 存信任设备（Peer）表，而它的 dex 里 `androidx/room/*` **定义数为 0**（只有引用） |

> 判定「类到底有没有被抽到」**必须解析 dex 的 `class_defs`**，不能只 grep 字符串池 ——
> 字符串池里出现 `Landroidx/room/RoomDatabase;` 既可能是**定义**、也可能只是**引用**。

还有一条：**启动 SDK 必须等运行时权限**。`BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT`（API 31+）
未授予时 `startAdvertise()` 会**静默失败**。MiAuto 用幂等的 `ensureIcocaStarted()`
（`onCreate` + `onResume` 各调一次）兜住。

## 4. 与 Android Auto 的资源隔离

MiAuto 只把 `AapService` 当前台保活，并用 `BuildConfig.ICCOA_ONLY = true` 关掉它的 AA 专属副作用：

- `enableCarMode()` —— 会与车机自己的 UI 抢前台
- `MediaSession.isActive` —— 会抢车机媒体键
- `WifiDirectManager` / `SoftApCredentialsProvider` / `LocalHotspotCredentialsProvider` / `NearbyManager`
  —— **会抢占 ICCOA 投屏数据通道要用的 WiFi P2P 组与 SoftAP**

`AapService` 里的这些字段全是可空且下游一律用 `?.`，所以不构造它们即可安全跳过（下游自动 no-op）。

## 5. 配对码为什么必须「周期性重播」

真机实测暴露了一个只做「断开后刷新一次」解决不了的问题：

> 断开后确实刷新了新码，但如果当时没人连（手机关掉了配对弹窗），之后码就不再变，
> 手机也再不会弹窗 —— 只能重启应用才能重连。

**根因**：车机「一直在广播」不等于「手机能重新发现」。BLE 广播帧内容不变时，手机端 CarWith
**不会重新弹出**配对界面。

**解法**（`com.miauto.app.IccoaManager`）：

| 机制 | 说明 |
|---|---|
| **30 秒看门狗** | `AUTO_REFRESH_INTERVAL_MS = 30_000`：到期若仍未连接 → 重播 → 再调度下一轮 |
| **★ 重播 = 停-启** | `stopAdvertise()` → `startAdvertise()`。**对一条已在广播中的链路重复调用 `startAdvertise()` 会被 SDK 直接忽略**，广播帧不变，手机端什么也不会发生 —— 这是最容易被漏掉的一步 |
| **避让** | `sessionActive`（`newState 2/3` 已连）或 `castStarted`（画面已投出）时跳过，绝不打断进行中的会话；`newState 1`（CONNECTING）**刻意不算**，好让协商卡死时能被重播救回来 |
| **计时重置** | `onPinCode` 时重置计时，保证用户拿到完整的 30 秒去确认 |
| **手动入口** | 首页「刷新配对码」按钮 → `IcocaBridge.refreshPairing()`，与看门狗同一条路径，只是不等 30 秒；仅未连接时可见 |

顺带修掉一个隐患：`tryStartCast()` 原先在无 Surface（待机页）时会**每 500 ms 无限自我重试**，
待机页常驻即等于永不停止的定时器。现已加 `CAST_RETRY_MAX = 20`（≈10 秒）预算，
Surface 稍后就绪的情形由 `setSurface()` 兜底触发。

## 6. 32 位设备启动崩溃：`libusbio.so`

ICCOA 的 `ShareLinkService.onBind()` 会启动 AOA USB 管理器，**无线模式也会走这条路径**。
`com.ucar.connect.aoa.UsbNative` 在静态初始化时无条件 `System.loadLibrary("usbio")`。
已分发的 0.1.1 APK 仅在 `arm64-v8a` 下带此库；32 位 Android 9 设备的实测崩溃为
`UnsatisfiedLinkError: couldn't find "libusbio.so"`。应用自身的 `usbhelper` 是另一套 JNI
接口，无法通过改名来替代它。

CMake 现在对缺少厂商预编译库的 ABI 构建 `usbio_compat.c`，提供 SDK 实际使用的
`nativeBulkRead` / `nativeBulkWrite`，签名均为 `(II[BIIJ)I`。实现通过 Android USB
连接已经打开的 fd 执行 `USBDEVFS_BULK`，保留数组偏移、短读和负 errno 返回值；不关闭
由 SDK 拥有的 fd，也不在阻塞传输期间固定 Java 数组。已有的 arm64 厂商库保持原样。

验证：`bash scripts/test_usbio.sh` 在 Linux JVM 上测试真实 JNI 绑定、数组边界、偏移、
短读和驱动错误；`python3 scripts/check_apk_native_libs.py <APK>` 检查每个打包 ABI 都含
正确架构的 `libusbio.so`。两项检查均已接入 Android CI。

这项修复解决 USB 库加载导致的闪退。其他 ABI 的 `libovmsink.so`（Miracast）和软件
HEVC 支持仍取决于实际提供的库；它也不会改变 ICCOA 的系统权限要求。

## 7. Android 9 无线连接兼容

设备端以应用 UID 实测：公开 `WifiP2pManager.initialize()` 和经典 `createGroup()`
可以成功建组、取得连接参数并清理；而旧热点路径所调用的 `getCountryCode()` 被
`CONNECTIVITY_INTERNAL` 拒绝。Android 9 因此只声明支持 P2P，让 SDK 现有的
`phone expect ap connection, but car only supports p2p connection` 分支负责协商。
这不代表已经验证 CarWith 端到端投屏，仍需真机连接测试。

SDK 回调是 `onConnectStateChanged(deviceID, state, parameter)`：1 为断开、2 为连接、
3 为连接失败、4 为投屏已连接；失败时 parameter 是 10003/10008 等错误码。
适配层必须传递 state，不能将这两个整数当成 oldState/newState。

未连接的重播间隔延长至 5 分钟；收到连接进度后取消重播，防止协商过程中关闭 BLE、
更换配对码。SDK 报告断开或连接失败后恢复重播，手动刷新按钮仍可立即重试。

## 8. 公开本地热点备选

设置中的「CarWith 连接方式」提供自动、Wi-Fi Direct、本地热点三项。自动保留原策略；
本地热点仅声明 AP 能力，由 `PublicHotspotService` 调用公开 `startLocalOnlyHotspot()`。
该 API 自 Android 8 起可用；本项目通过 Android 9 引入的 `AppComponentFactory`
替换 SDK 绑定的热点服务，因此这一备选模式要求 Android 9 或以上。

适配器保留 SDK v1.5.3 的组件名和 Binder 描述符，按已核对的事务编号传递请求和回调，
不导入或修改私有 SDK。`onApCreated` 的参数顺序是 IP、SSID、密码、MAC、频率。
系统生成的实际 SSID/密码会回传 SDK；不假装能设置指定频道，未知频率传 0，未知 MAC
传空值（SDK 本身有占位地址处理）。不会以现有路由器的 IP 或 BSSID 冒充热点参数。

公开接口只报告热点就绪、不提供普通应用可用的客户端列表。适配器在地址和参数就绪后
发送 SDK 的传输就绪事件，手机会话仍由 SDK 的鉴权和连接状态回调确认；热点启动成功
不代表手机已连接或投屏成功。本地热点不提供互联网共享。

每次请求有代次标记，取消后的迟到 reservation 会立即关闭；失败、平台停止、SDK 关闭
和服务销毁都会释放本应用持有的 reservation。不会调用系统热点、国家码设置或关闭
用户 Wi-Fi 的私有接口。Android 10+ 的配置类型被隔离，避免 Android 9 提前解析新类型。

模式变更后选择「保存并退出」，重新打开 MiAuto，让进程内只初始化一次的 SDK 使用新
传输配置。调试构建支持限时探测 action `APPLICATION_ID.PROBE_PUBLIC_HOTSPOT`；
10 秒后自行释放热点，即使无线 ADB 断开也会清理。正式构建不执行这一探测入口。
