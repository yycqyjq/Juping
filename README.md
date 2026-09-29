# 聚屏（Juping）

把老电视盒子变成投屏接收端。手机上的腾讯视频 / 哔哩哔哩 / 爱奇艺 / QQ音乐 点「投屏」，画面就上电视。

**目标设备**：Android 4.0.4（API 15）· MediaTek MT5880 · 0.6GB RAM

---

## 为什么不用现成的

| 方案 | 最低要求 | 结论 |
|---|---|---|
| 乐播投屏 TV 版 8.20.56 | Android 4.1（minSdk 16） | 装不上。且官方已停服 TV 版 ≤8.3.10 |
| 当贝投屏 / 爱投屏 | Android 5.0+ | 装不上 |
| 小柚投屏 | Android 4.4+ | 装不上 |
| SMG投屏助手 | Android 6.0+ | 装不上 |
| 盒子自带的 | — | 容易断联 |

自带的那个之所以断，根因是 **Miracast / Wi-Fi Direct**：它要求手机和盒子直连一条 P2P 链路，射频层面受干扰、驱动层面各家实现不一，掉线是常态。

**聚屏走的是 DLNA（普通局域网 HTTP）**，不碰 Wi-Fi Direct，天然没有这个问题。

---

## 它怎么工作

```
手机 App                   盒子（聚屏）
   |                          |
   |-- 1. SSDP 组播搜索 ----->|  239.255.255.250:1900
   |<-- 2. 单播回应 ----------|
   |                          |
   |-- 3. 取设备描述 XML ---->|  GET /upnp/device.xml
   |<-- 4. 描述 + 服务列表 ---|
   |                          |
   |-- 5. SetAVTransportURI ->|  POST /upnp/control/AVTransport
   |   （把视频 URL 交过来）   |       ↓
   |<-- 6. 播放状态回报 ------|  MediaPlayer 直接播这个 URL
```

关键点：**手机推过来的是一个 URL，不是视频流本身**。所以盒子不需要解码手机的画面，只需要用系统自带的 `MediaPlayer` 硬解那个 URL —— 芯片是什么都无所谓，这就是「全兼容」的来源。

---

## 目录结构

```
Juping/
├── app/src/main/
│   ├── AndroidManifest.xml          权限、组件、开机自启声明
│   ├── java/com/juping/cast/
│   │   ├── MainActivity.java        电视界面：等待投屏 / 播放中 双形态
│   │   ├── BootReceiver.java        开机自启
│   │   ├── DlnaRendererService.java 前台服务心脏（DLNA + 播放器都挂这儿）
│   │   ├── dlna/
│   │   │   ├── SsdpResponder.java   组播监听 + 设备回应
│   │   │   └── UpnpHttpServer.java  HTTP 服务 + SOAP 控制解析
│   │   └── player/
│   │       └── MediaPlayerController.java  播放 + 看门狗 + 指数退避重连
│   └── res/                         布局、配色、字符串、图标、banner
└── tools/
    ├── build.sh              一键构建 + 出包前核验
    ├── check_api_compat.py   逐个核验平台 API 引用是否在目标版本里存在
    ├── make_icon.py          生成全部位图资源（纯标准库）
    ├── probe-tv.sh           adb 探测盒子真实硬件信息
    ├── apk_info.py           解析 APK 的包名 / minSdk
    └── check_sources.py      无 JDK 环境下的源码结构检查
```

---

## 编译

工具链全部在 `~/.android-build/` 下自成一套，不污染系统（本机没有系统级 JDK / Android SDK / brew）。

```bash
./tools/build.sh              # 编译 debug APK
./tools/build.sh release      # 编译已签名的 release APK
./tools/build.sh dist         # 两个都编，跑全部核验，成品归集到 dist/
./tools/build.sh lint         # 跑 lint（API 兼容性检查）
./tools/build.sh checkapi     # 逐个核验平台 API 引用是否在目标版本里存在
./tools/build.sh clean
```

产物：

```
dist/juping-0.1.0-release.apk   ← 装机用这个（43K，已签名）
dist/juping-0.1.0-debug.apk     ← 排障用（49K，带 debuggable 标记）
```

`dist` 目标会在归集后**自动跑两项核验**，任何一项不过就报错退出 —— 免得把一个装不上的包交出去：

1. **签名**：以 API 15 为目标验证（`apksigner verify --min-sdk-version 15`）
2. **API 兼容性**：逐个核对 dex 里引用的每个平台成员在目标版本里是否真的存在

如果工具链已在 PATH 里，也可以直接用 wrapper：

```bash
./gradlew assembleRelease
```

工具链需求：

| 组件 | 版本 | 位置 |
|---|---|---|
| JDK | 17 (Temurin aarch64) | `~/.android-build/jdk/Contents/Home` |
| Gradle | 7.5 | `~/.android-build/gradle` |
| Android SDK | platform-33 + build-tools 33.0.0 | `~/.android-build/sdk` |
| **基线 android.jar** | **API 14 与 15** | `~/.android-build/sdk/platforms/android-{14,15}` |

最后一行是做 API 兼容性核验用的。没有它 `checkapi` 会跳过（不会失败，但也就等于没查）：

```bash
curl -o android-15.zip https://dl.google.com/android/repository/android-15_r05.zip
curl -o android-14.zip https://dl.google.com/android/repository/android-14_r04.zip
# 解压后把 android.jar 放到 sdk/platforms/android-{15,14}/
```

---

## 签名

release 密钥在 `keystore/juping-release.jks`，密码在 `keystore.properties`。
**这两个文件都被 `.gitignore` 忽略**，不会跟着代码跑出去。

> **务必备份这两个文件。**
> 密钥丢了，就没法对已经装在盒子上的聚屏做覆盖升级 —— 只能先卸载再装，
> 而卸载会清掉已保存的设备 UUID（手机投屏列表里会多出一台"新"设备）。

### 为什么密钥用 SHA1withRSA

`keytool` 生成时会警告「SHA1 是弱算法」。这是**刻意的取舍**：

- 目标设备是 Android 4.0.4（API 15）。API 18 以下的平台对签名算法支持很窄，
  `SHA1withRSA` 是唯一能确定被认的。
- 这把密钥只用于本地侧载，不参与任何信任链。SHA-1 的碰撞攻击面在
  「自己签自己」的场景下不构成实际风险。

**兼容性 > 理论强度**，这是老设备上的必然选择。

同理，APK 的 v1（JAR）签名摘要也必须是 SHA-1 —— 这一点由 `apksigner` 根据
`minSdkVersion` 自动决定，本项目 minSdk=14，所以自动就是 SHA-1。
可以用这个命令确认：

```bash
unzip -p dist/juping-0.1.0-release.apk META-INF/MANIFEST.MF | grep Digest
# 应该看到 SHA1-Digest: ...，而不是 SHA-256-Digest
```

---

## 安装与首次启动

```bash
adb connect <盒子IP>:5555        # 或 USB 连接
adb install -r dist/juping-0.1.0-release.apk
```

> **装完必须手动打开一次。**
>
> 从 Android 3.1 起，处于「已安装但从未被启动过」状态的应用收不到 `BOOT_COMPLETED` 广播。
> 也就是说：装完 → 手动点开一次 → 之后开机才会自动启动。
>
> 这不是 bug，是系统的安全设计（防止流氓应用装完就常驻），绕不过去。


---

## 稳定性是怎么保的

老盒子 + 0.6GB 内存，稳定性不是靠「写好点」，是靠一层层兜底：

| 机制 | 防的是什么 |
|---|---|
| **前台服务** + `START_STICKY` | 被系统/低内存杀手回收 |
| **WifiLock** (`FULL_HIGH_PERF`) | 息屏后 Wi-Fi 降频断流 |
| **MulticastLock** | 收不到 SSDP 组播（表现为「手机搜不到设备」） |
| **看门狗** | 播放卡死不动 —— 点播 20s、直播 60s 两档阈值 |
| **指数退避重连** | 1s → 2s → 4s → 8s → 16s，最多 5 次 |
| **卡死熔断** | 连续卡死 3 次就停手，避免无限重连反而打断播放 |

看门狗为什么要两档阈值：点播流的 `getDuration()` 有值，位置 20 秒不动就是真卡了；而 HLS 直播的位置可能长时间不增长甚至恒为 0，用 20 秒判会把正常播放误杀成卡死。

---

## 兼容性红线

代码按 android-33 编译，但**运行在 API 15**。以下都是「编译得过、真机上崩」的坑，已在 `app/build.gradle` 的 `lint` 块里显式关闭并注明理由：

| 不能用 | 因为 |
|---|---|
| `FLAG_IMMUTABLE`（PendingIntent） | API 23 才有 → `NoSuchFieldError` |
| `layout_marginStart` / `paddingStart` | API 17 才有 → 只能继续用 `Left/Right` |
| `MediaCodec` | API 16 才有 —— 本项目全程不碰它 |
| AndroidX 任何组件 | 普遍要求 minSdk 19+ → 直接编译不过 |

### 两道独立的验证

光靠编译过是不够的 —— 对着新版 android.jar 编译，调用新 API 完全不会报错。

**第一道：lint 的 `NewApi` 检查**

```bash
./tools/build.sh lint
```

看 `NewApi` 有没有命中。当前为 **0 命中**，即没有任何 API 调用超出 API 14。

**第二道：直接对着目标版本的 android.jar 核**

```bash
./tools/build.sh checkapi
```

`tools/check_api_compat.py` 会把 dex 里引用的每个平台成员抠出来，逐个到
API 14 / API 15 的 `android.jar` 里查（含 extends / implements 继承链递归）。
当前结果：

```
被引用的平台类 51 个 · 方法 161 个 · 字段 3 个
结论：164 个平台引用全部命中，无 API 越界。
```

为什么要两道：lint 依赖内置数据库，而且本项目关掉了 8 项检查 ——
万一其中某一项顺带掩盖了 API 问题，lint 不会吭声。第二道是**独立判据**。

> **这个检查器做过反向验证**：临时在代码里插入一个 API 23 的调用
> （`MediaPlayer.setPlaybackParams`），javac 编译毫无怨言，而检查器精准抓出了它。
> 一个只会说"通过"的检查器是没有价值的。

---

## 已知边界

- **乐联（LeLink）协议不支持**。B站、抖音、部分腾讯视频走的是乐播的私有闭源协议，开源界没有实现，无法对接。能收的是标准 DLNA / UPnP 推送。
- **AirPlay 未实现**。iOS 侧目前只能用支持 DLNA 的 App 投。要做 AirPlay 接收需要移植 UxPlay（C/C++，GPLv3），是独立的一大块工作。
- **镜像（Miracast）不做**。老盒子 Wi-Fi Direct 驱动不稳，正是断联根因，不值得修。
- **从未在真机上运行过**。已通过五项静态核验（编译 / lint `NewApi` 零命中 /
  API 引用 164 项全命中 / DEX 版本 035 / 签名在 API 15 上有效），
  但真机上的组播收发、MediaPlayer 硬解、断联恢复都还没实测。

---

## 排障

电视上直接能看到诊断信息，不用连电脑：

- **设备名** — 手机投屏列表里应该出现的名字（`聚屏-<型号>`）
- **地址** — 盒子的 IP:端口。手机搜不到时，先确认手机和盒子在同一个网段
- **网络** — 组播实际绑在哪个网卡上（有线优先）。显示 `(未启动)` 说明组播没绑上
- **状态** — 等待投屏 / 正在播放 / 已暂停 / 出错

遥控器按「重启服务」可以原地重启整个接收端，不用拔电。
