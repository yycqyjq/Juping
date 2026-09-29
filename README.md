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
   |-- 5. SUBSCRIBE 订阅 ---->|  SUBSCRIBE /upnp/event/AVTransport
   |<-- 6. SID + TIMEOUT -----|
   |<-- 7. 初始事件 NOTIFY ---|  SEQ=0，含当前全部事件变量
   |                          |
   |-- 8. SetAVTransportURI ->|  POST /upnp/control/AVTransport
   |   （把视频 URL 交过来）   |       ↓
   |<-- 9. 状态事件 NOTIFY ---|  MediaPlayer 直接播这个 URL
   |                          |  （播放/暂停/换片一变就推一条，SEQ 递增）
```

关键点：**手机推过来的是一个 URL，不是视频流本身**。所以盒子不需要解码手机的画面，只需要用系统自带的 `MediaPlayer` 硬解那个 URL —— 芯片是什么都无所谓，这就是「全兼容」的来源。

第 5–7 步（GENA 事件订阅）和 9 是**双向**的：手机不只是发指令，它还要知道盒子上到底发生了什么。少了这一段，手机上的播放进度条和播放/暂停按钮就会一直停在旧状态 —— 用户按了暂停，手机上还显示在播。

---

## 目录结构

```
Juping/
├── app/src/main/
│   ├── AndroidManifest.xml          权限、组件、开机自启声明
│   ├── java/com/juping/cast/
│   │   ├── MainActivity.java        电视界面：等待 / 视频 / 音乐 三形态
│   │   ├── BootReceiver.java        开机自启
│   │   ├── DlnaRendererService.java 前台服务心脏（DLNA + 播放器都挂这儿）
│   │   ├── dlna/
│   │   │   ├── NetUtil.java         网卡选择的唯一出处（SSDP 与 LOCATION 共用）
│   │   │   ├── SsdpResponder.java   组播监听 + 设备回应
│   │   │   ├── EventDispatcher.java GENA 事件订阅与 NOTIFY 推送（自带单线程池保 SEQ 有序）
│   │   │   └── UpnpHttpServer.java  HTTP 服务 + SOAP 控制解析 + SUBSCRIBE 路由
│   │   └── player/
│   │       ├── PlaybackPolicy.java         重连策略：纯逻辑，零 Android 依赖
│   │       └── MediaPlayerController.java  播放 + 看门狗 + 指数退避重连 + 音量状态
│   └── res/                         布局、配色、字符串、图标、banner
└── tools/
    ├── build.sh              一键构建 + 出包前核验
    ├── check_api_compat.py   逐个核验平台 API 引用是否在目标版本里存在
    ├── make_icon.py          生成全部位图资源（纯标准库）
    ├── probe-tv.sh           adb 探测盒子真实硬件信息（只读）
    ├── verify-on-device.sh   一条命令真机验收：装包 → 起服务 → 自检
    ├── dlna-probe.py         控制点视角自检（站在手机那一侧走完整链路）
    ├── apk_info.py           解析 APK 的包名 / minSdk
    ├── check_no_secrets.py   发布前核查：密钥真实值有没有混进被跟踪的文件
    ├── check_sources.py      无 JDK 环境下的源码结构检查
    ├── protocol-test/        DLNA 协议层端到端测试（桌面 JVM，不需要真机）
    │   ├── run.sh            编译 → 起服务 → 驱动 → 验证两个自检脚本
    │   ├── drive.py          154 项一致性检查（原始 socket 精确控字节）
    │   ├── ProtocolTestServer.java  在桌面跑真实的 UpnpHttpServer + SsdpResponder
    │   ├── verify-device-selftest.sh  用假 adb 验 verify-on-device.sh 的管道
    │   └── android/util/Log.java    android.util.Log 的桌面替身
    └── policy-test/          播放重连策略测试（纯逻辑，不需要真机）
        ├── run.sh            编译 + 46 项断言 + 80 条源码级不变量守卫
        └── PolicyTest.java   46 项断言 + 「卡死→重连→又卡死」循环模拟
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
./tools/build.sh protocol     # 跑 DLNA 协议层一致性测试（不需要真机）
./tools/build.sh policy       # 跑播放重连策略测试（纯逻辑，不需要真机）
./tools/build.sh clean
```

产物：

```
dist/juping-0.1.1-release.apk   ← 装机用这个（已签名）
dist/juping-0.1.1-debug.apk     ← 排障用（带 debuggable 标记）
```

`dist` 目标会在归集后**自动跑五道闸**，任何一道不过就报错退出 —— 免得把一个装不上的、投不进来的、断联后恢复不了的、或者带着签名密钥的包交出去：

1. **签名**：以 API 15 为目标验证（`apksigner verify --min-sdk-version 15`）
2. **API 兼容性**：逐个核对 dex 里引用的每个平台成员在目标版本里是否真的存在
3. **协议层**：把真实的 UPnP 服务编到桌面 JVM 上，发真实 DLNA 报文核对响应
4. **播放策略**：退避表 / 卡死阈值 / 熔断边界 + 「卡死→重连→又卡死」循环模拟
5. **密钥核查**：确认签名密钥的真实值没有混进任何被跟踪的文件（这个仓库是公开的）

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
unzip -p dist/juping-0.1.1-release.apk META-INF/MANIFEST.MF | grep Digest
# 应该看到 SHA1-Digest: ...，而不是 SHA-256-Digest
```

---

## 安装与首次启动

### 先确认这台盒子有没有可用的 adb

`adb` 是首选，但它不是唯一路径，而且**不是所有老盒子都有**。2012 年前后的 MT5880
方案里，有相当一部分 ROM 把开发者选项整个裁掉了，或者 adb 只在工程模式/串口下可用。

先在盒子侧翻一下：`设置 → 开发者选项` 里的 **USB 调试** / **网络调试**。
能开就走路径 A；翻不到这一项就直接走路径 B，**别在 adb 上耗时间**。

### 路径 A：adb（USB 或局域网）

```bash
adb connect <盒子IP>:5555        # 或 USB 连接
adb install -r dist/juping-0.1.1-release.apk
```

局域网 adb 需要盒子侧已经开着网络调试并在监听 5555 —— 零售盒子默认是关的，
所以这条路通常只在 USB 下才通。

### 路径 B：U 盘（最通用，不依赖任何调试通道）

1. 把 `dist/juping-0.1.1-release.apk` 拷到 U 盘。**用 FAT32** ——
   老盒子对 exFAT / NTFS 的支持看 ROM 心情，FAT32 是唯一稳的
2. U 盘插上盒子，用盒子自带的「文件管理 / 本地媒体 / USB 设备」找到这个文件
3. 点它安装

两个常见拦路虎：

- **盒子没有文件管理器。** 精简 ROM 常把它去掉。可以往 U 盘里再放一个第三方
  文件管理器的 APK 一起装；如果连这都装不了，走路径 C。
- **提示「解析包错误」**（`INSTALL_PARSE_FAILED_*`）。先别怀疑代码 ——
  九成是 U 盘里那个文件本身不完整（拷贝中断、拔盘太早、FAT32 写入没落盘）。
  和源文件比一下大小，不一致就重拷一遍：

  ```bash
  ls -l dist/juping-0.1.1-release.apk   # 记下这个字节数，再和 U 盘里那个比
  # 两个数一致就说明拷完整了。
  # 刻意不写死具体数字 —— 每次重新构建都会变，写死的那份迟早对不上，
  # 反而会让人以为文件拷坏了（这里原来就写着一个过期的字节数）。
  ```

### 路径 C：局域网 HTTP（盒子有浏览器，或能装文件管理器）

在 Mac 上把 `dist/` 挂成静态服务，盒子侧用浏览器或下载器取：

```bash
cd dist && python3 -m http.server 8000
# 盒子浏览器打开 http://<Mac 的 IP>:8000/juping-0.1.1-release.apk
```

### 共同前提：允许「未知来源」

Android 4.0 上在 **设置 → 安全 → 未知来源** 打勾。不打勾安装会被直接拒掉，
而且提示往往很含糊（只有一句「应用未安装」），容易误判成包坏了。

### 装完必须手动打开一次

> 从 Android 3.1 起，处于「已安装但从未被启动过」状态的应用收不到 `BOOT_COMPLETED` 广播。
> 也就是说：装完 → 手动点开一次 → 之后开机才会自动启动。
>
> 这不是 bug，是系统的安全设计（防止流氓应用装完就常驻），绕不过去。

打开后看到界面上出现**设备名**和**地址**，就说明服务起来了。这时用手机上的
投屏 App 搜一下，应该能搜到「聚屏-\<型号\>」。


---

## 稳定性是怎么保的

老盒子 + 0.6GB 内存，稳定性不是靠「写好点」，是靠一层层兜底：

| 机制 | 防的是什么 |
|---|---|
| **前台服务** + `START_STICKY` | 被系统/低内存杀手回收 |
| **WifiLock** (`FULL_HIGH_PERF`) | 息屏后 Wi-Fi 降频断流 |
| **MulticastLock** | 收不到 SSDP 组播（表现为「手机搜不到设备」） |
| **网卡选择要挑有 IPv4 的那张** | 绑到隧道/蜂窝接口（`utun3` / `rmnet_data0`）会让 SSDP 静默死掉 |
| **网卡依次重试** | 候选列表里第一张 `joinGroup` 失败就换下一张，不一次就放弃 |
| **看门狗** | 播放卡死不动 —— 点播 20s、直播 60s 两档阈值 |
| **指数退避重连** | 1s → 2s → 4s → 8s → 16s，最多 5 次 |
| **卡死熔断** | 连续卡死 3 次就停手，避免无限重连反而打断播放 |
| **旧重连可取消** | 换新视频时掐掉上一个视频排期的重连，免得把新画面顶掉 |

看门狗为什么要两档阈值：点播流的 `getDuration()` 有值，位置 20 秒不动就是真卡了；而 HLS 直播的位置可能长时间不增长甚至恒为 0，用 20 秒判会把正常播放误杀成卡死。

---

## 界面三态

电视上只有三种形态，靠播放状态自动切换：

| 形态 | 什么时候 | 显示什么 |
|---|---|---|
| **等待投屏** | 没有内容 | 引导卡片 + 设备信息（名字 / 地址 / 网络 / 状态） |
| **视频播放** | 有内容，且含画面 | **纯画面，全屏，顶上什么都没有** |
| **音乐播放** | 有内容，但**纯音频** | 音符卡片：音符 + 「音乐投屏」+ 片源 + 进度 |

### 顶部状态条只在「有话说」的时候出现

它原来是一进入播放就常显的 —— 于是看视频时画面上永远压着一条半透明黑带。
现在按状态决定：

| 什么时候 | 状态条 |
|---|---|
| 正常播放中 | **隐藏**（视频就是纯画面） |
| 暂停 | 出现，报「停在哪」 |
| 出错 | 出现，报错因 |
| 缓冲 / 重连中（`TRANSITIONING`） | 出现，说明画面为什么还没出来 |
| 等待投屏 | 隐藏（主面板已经在报信息了，再叠一条就重复） |

为什么不干脆删掉：**暂停和出错的那一刻，屏幕上恰好没有任何别的反馈。**
黑屏卡住时要是连它都没有，电视端就彻底没有出口了 —— 只能去连电脑抓 logcat。
所以它不是装饰，是「只在需要时才出现」的排障出口。

状态条左边那个圆点**会跟着变色**：出错亮红、缓冲 / 重连中亮蓝、其余绿。
它原来是**写死的绿点** —— 而状态条现在恰恰只在出错 / 暂停时出现，
于是会出现「绿点 + 出错：…」这种自己跟自己打架的画面。
三米外先被看见的是颜色而不是那行小字，所以颜色必须说实话
（主面板上那个圆点早就改成这样了，这里当时漏了）。

这里有一个不做就会静默失效的坑，同样钉进了源码级不变量：
**它的可见性必须每个刷新周期重算，不能只写在 `applyMode()` 里。**
暂停与出错是**同一个形态内部**的变化 —— 形态没切换，`applyMode()` 根本不会被走到，
于是「播放中途按暂停，状态条永远不出现」。编译、运行、日志全都正常。

### 进度刷新为什么分两档

| 状态 | 间隔 | 为什么 |
|---|---|---|
| 等待投屏 | 1500ms | 面板上的信息几乎不变，刷快了纯属浪费（这台设备只有 0.6GB 内存） |
| 播放中 | 500ms | `01:23 / 03:45` 是用户唯一能对照手机进度条的东西；1.5 秒一跳，拖完总要愣一下才跟上，看起来就像「没同步」 |

播放期间还会**跳过主面板的文字刷新** —— 那几行（设备名 / 地址 / 网卡）在播放过程中
根本不会变，而面板此时本来就是隐藏的。省下的开销正好抵掉提速那部分。

进度显示还有一道**钳制**：位置短暂越过总时长时不显示越界数字（HTTP 流的分段时长
估算是会浮动的）。不钳的话电视上会出现 `10:30 / 03:45` 这种数，看着就像进度「满了」。

### 为什么「音乐播放」必须单独做一层

纯音频流走的是**同一个 `SurfaceView`**，上面什么都没有 —— 电视就是**一片黑**，
只剩一条状态栏。声音明明在放，看着却像投屏坏了。音乐是主要用途之一，
这个误判的代价很高，所以必须换成音符卡片。

这里有两个不做就会前功尽弃的细节，都写进了源码级不变量：

- **音乐层必须画在 `SurfaceView` 之后**。`FrameLayout` 里后画的在上面，
  顺序反了就会被画面层盖住 —— 而画面层此时是全黑的，等于没做。
- **音乐层的背景必须完全不透明**（`@color/bg`，alpha = `FF`）。
  半透明或透明都会让底下那片黑透出来。

三态用**一个整数**而不是两个布尔表示。两个布尔允许出现「既是音频又是视频」
这种非法组合，而它一旦出现，界面会同时显示画面和音符卡片 —— 排查起来非常费劲。

「是不是纯音频」这个判断的权威来源在服务里（`isAudioOnly()`），界面不自己猜：
它由**两个信号合并**得出 —— 控制点传的 DIDL-Lite 里 `upnp:class` 是
`object.item.audioItem` 还是 `object.item.videoItem`，以及 `MediaPlayer` 报的
真实视频尺寸（`getVideoWidth()`）。

> 为什么不用 `getTrackInfo()` 判断有没有视频轨：**那是 API 16 才有的**。
> 而 `getVideoWidth()` 从 API 1 就在，纯音频返回 0 —— 这是 API 15 上唯一可靠的判据。
>
> 元数据只是「提示」，很多控制点根本不传它，所以两个信号都要用；
> `getVideoWidth()` 抛异常时**按「有视频」处理**：宁可退回原来的黑屏，
> 也不要对着一个视频弹出音乐卡片 —— 后者更离谱，也更难解释。

---

## 兼容性红线

代码按 android-33 编译，但**运行在 API 15**。以下都是「编译得过、真机上崩」的坑，已在 `app/build.gradle` 的 `lint` 块里显式关闭并注明理由：

| 不能用 | 因为 |
|---|---|
| `FLAG_IMMUTABLE`（PendingIntent） | API 23 才有 → `NoSuchFieldError` |
| `layout_marginStart` / `paddingStart` | API 17 才有 → 只能继续用 `Left/Right` |
| `MediaCodec` | API 16 才有 —— 本项目全程不碰它 |
| AndroidX 任何组件 | 普遍要求 minSdk 19+ → 直接编译不过 |

### 四道深度验证

上面五道闸里，签名与密钥核查各管一件事、判据单一。这四道不一样：
它们**各自独立实现、互为判据**，而且每一道都做过反向验证（能红才算测过）。

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
被引用的平台类 63 个 · 方法 211 个 · 字段 3 个
结论：215 个平台引用全部命中，无 API 越界。
```

为什么要两道：lint 依赖内置数据库，而且本项目关掉了 8 项检查 ——
万一其中某一项顺带掩盖了 API 问题，lint 不会吭声。第二道是**独立判据**。

> **这个检查器做过反向验证**：临时在代码里插入一个 API 23 的调用
> （`MediaPlayer.setPlaybackParams`），javac 编译毫无怨言，而检查器精准抓出了它。
> 一个只会说"通过"的检查器是没有价值的。

**第三道：DLNA 协议层端到端测试**

前两道只管「装得上、跑不崩」，管不了「手机投得进来」。而整套 UPnP
（SSDP 组播发现 / 设备描述 XML / 三个 SOAP 服务）是**手写的、没用任何库**，
是全项目最容易出错、又最难在真机上调的部分。

好在 `dlna` 包对 Android 的依赖只有 `android.util.Log` 一个类 ——
补一个桌面替身，就能把**真实的** `UpnpHttpServer` 和 `SsdpResponder`
编到桌面 JVM 上，用原始 socket 发真实 DLNA 报文核对响应：

```bash
./tools/build.sh protocol
```

```
协议一致性：154 / 154 通过
```

覆盖两大故障场景 —— **「手机搜不到设备」和「投屏没反应」**：

| 段 | 查什么 |
|---|---|
| 1–2 | 设备描述 XML、三个服务的 SCPD 是否可取且合法 |
| 3–5 | SOAP 控制指令、`SetAVTransportURI` 中文元数据、播放状态机、**`Seek` 的 `Target` 格式族**（`00:10:30` / `00:10:30.000` / `00:10:30.5` / `0:10:30`，以及非法值必须被**忽略**而不是跳到 0） |
| **6** | **回读契约**：`CurrentURI` / `TrackURI` / `NrTracks` 三者必须自洽，带 `&` 的地址必须能原样回读 |
| 7 | 健壮性：未知路径 / 缺失头 / 畸形报文后服务是否还活着 |
| **8** | **SSDP 发现**：`ssdp:all` 要回全 6 个搜索目标；每个 ST 必须原样回、USN 格式正确；无关搜索与 NOTIFY 不能应答 |
| **9** | **发现链路闭环**：顺着 LOCATION 抓 device.xml → 校验 UDN/deviceType 与 SSDP 一致 → 逐个抓 SCPDURL → 每个 controlURL 可达 |
| **10** | **GENA 事件订阅**：SCPD 必须声明哪些变量可事件化；订阅后必须立刻收到 SEQ 0 的初始事件；状态一变必须推、SEQ 必须递增；非法订阅（CALLBACK 与 SID 同给/同不给、NT 不对、SID 不认识、服务名不对）必须回 412/404；退订后不许再推 |
| 11 | 请求行变体：绝对形式（`GET http://host/path`）、多余空格、带查询串 |

> **这套测试累计抓出六个真 bug**，每一个都能让投屏在真机上失效，
> 而真机上全都无从定位：
>
> **投屏侧（第 1–6 段）**
> ① 用 `BufferedReader.read(char[])` 读 HTTP body —— 那是**字符数**，
>    而 `Content-Length` 是**字节数**。含中文标题的投屏请求会一直阻塞到
>    socket 超时，**一个字节响应都不发**（真机上表现就是"投屏没反应"）。
> ② `extractActionName()` 把 XML 属性名吞进了 action 名
>    （`GetTransportInfo xmlns:u="..."`），导致指令全部无法匹配。
> ⑥ `GetMediaInfo` **永远回一个空的 `CurrentURI`**，哪怕视频正在播 ——
>    因为 `getCurrentUri()` 只写在服务类里，**没进 `CommandHandler` 接口**，
>    协议层根本调不到它。规范要求 `CurrentURI` 反映当前媒体，只有
>    `NO_MEDIA_PRESENT` 时才允许为空。后果不是"少个字段"这么轻：部分投屏
>    SDK 会在 `SetAVTransportURI` 之后回读 `GetMediaInfo`，拿 `CurrentURI`
>    与自己刚推的地址比对，**不一致就判定"这台设备没接收成功"，画面留在手机上不投了**。
>    顺带发现同一处还有两个漏洞：`NrTracks` / `Track` 恒回 1（无媒体时也谎报有片），
>    以及 URL 里的 `&` 未做 XML 转义 —— CDN 地址几乎必然带查询串，
>    不转义会让**整份 SOAP 响应变成非法 XML**，控制点那边是"整条报文解析失败"。
>
> **发现侧（第 8–10 段）**
> ③ SSDP 应答的 `ST` 写死成设备类型，**没有原样回控制点的搜索目标**。
>    控制点是拿 ST 匹配应答的 —— 搜 `upnp:rootdevice` 却收到 `MediaRenderer`，
>    这条应答会被直接丢弃，手机端显示"什么都没搜到"。
>    而且搜 `ssdp:all` 时规范要求对每个搜索目标**各回一条**，不是回一条完事。
>    这就是「同一个 App 有时搜得到、有时搜不到」的典型来源。
> ④ 网卡选择会选中**没有 IPv4 地址的隧道接口**（`utun3` / Android 上的
>    `rmnet_data0`、`tun0`、`p2p0`），`joinGroup` 直接抛 `EADDRNOTAVAIL`，
>    SSDP 线程静默死掉。而且当时 `boundPort` 是在 `joinGroup` **之前**赋值的，
>    于是界面还显示"已就绪"—— **谎报军情比失败更糟**。
> ⑤ `setReuseAddress(true)` 写在构造函数之后 = 空操作（那时已经 bind 了）。
>    1900 端口一旦被占（盒子自带的投屏服务、或上一次没退干净的自己），
>    整个 SSDP 线程静默死掉，表现为「设备突然搜不到」。
>
> 另外修掉一个 RFC 合规问题：HTTP 请求行允许**绝对形式**
> （`GET http://192.168.1.50:49152/upnp/device.xml HTTP/1.1`，RFC 7230 §5.3.2），
> 而服务端必须接受。原来直接拿路径比较，绝对形式下返回 404 →
> 手机「搜到了设备却投不了屏」。
>
> **事件侧（第 10 段）**
> ⑦ **订阅了却永远收不到事件**。原实现的 `SUBSCRIBE` 是「回个 200 + SID 就完事」，
>    从不推 `NOTIFY` —— 而且 `RenderingControl` 的 SCPD 里**一张 stateVariable
>    表都没有**，控制点订阅到的是空事件集。对只发指令不回读的控制点没影响，
>    对依赖事件同步的那些（BubbleUPnP、各种遥控类 App）等于坏了一半：
>    手机上的播放进度条、播放/暂停按钮会一直停在旧状态。
>    这里有两个必须写进注释的坑：**① `HttpURLConnection.setRequestMethod("NOTIFY")`
>    会抛 `ProtocolException: Invalid HTTP method`** —— 它只认白名单里的方法，
>    所以 NOTIFY 必须用原始 socket 手写请求行；**② 初始事件必须晚于 200 响应** ——
>    控制点是拿响应里的 SID 认事件的，NOTIFY 早到会被当成未知 SID 直接丢掉
>    （Cling 等协议栈就是这么干的）。为此把「注册订阅」和「推初始事件」拆成
>    `subscribe()` / `fireInitial()` 两个方法，由 HTTP 层在 `out.flush()` **之后**调用后者。
> ⑧ **音量回读恒为 100**。`GetVolume` 的实现就是 `return 100;` ——
>    控制点拖完音量条再读一次，看到的是跳回 100。这不是「少个功能」，
>    是**对着控制点撒谎**：它会把音量条重新画到 100%。
>    根因是 `MediaPlayer.getVolume()` 是 API 23 才有的，代码里干脆没记这个值。
>    修法是自己在 `MediaPlayerController` 里维护音量（**先记再下发**），
>    并在重连重建播放器实例后**把音量重放一遍** —— 否则一次断流就把音量拉回满格。
> ⑨ **测试靶机比真机宽容**（这条是测试自己的问题，但值得记）。靶机的 `onPlay()`
>    无条件置 `PLAYING`，而真机上 `MediaPlayerController.stop()` 会把 `currentUrl`
>    清成 null，之后再 `Play` 是**空操作**、不会进 `PLAYING`。于是靶机被驱动到了
>    真机到不了的状态，下游自检脚本据此报出一条根本不存在的「不一致」。
>    **测试替身比被测对象宽容，就会制造假故障** —— 已按真机语义收紧。
> ⑩ **拖进度条会把播放打回开头 / 打到结尾**。`Seek` 的 `Target` 解析器只认
>    `H:MM:SS`，遇到 `.` 直接抛 `NumberFormatException`，被
>    `catch (Exception ignored)` 吞掉后 **`return 0`**。而 DLNA 规范里
>    `REL_TIME` 的定义就是 `H+:MM:SS[.F+]` —— **小数部分合法**，
>    安卓侧不少投屏 SDK 正是按 `00:10:30.000` 发的。于是「拖到 10:30」变成
>    「跳回开头」；控制点接着回读 `GetPositionInfo` 拿到 `RelTime=00:00:00`，
>    手机自己的进度条也跟着弹回 0。**而日志里一个字都没有**，因为异常被吞了。
>
>    教训：**任何「解析失败」都必须与「解析出 0」区分开。** 对时刻来说，
>    0 是一个完全合法、语义又极重的值（跳到开头）—— 拿它当兜底值，等于把
>    「看不懂」翻译成「从头开始」。现在解析失败一律返回 `-1`，
>    调用方**忽略**而不是下发；`Unit` 不是时间轴（`TRACK_NR`）时也不再当时刻解析。
>
>    另外补了一道**越界保护**：目标超过总时长就不下发 —— `MediaPlayer.seekTo()`
>    一旦越过末尾，位置会直接落到结尾并触发播放完成，用户看到的就是
>    「进度直接满了、声音也没了」。这个越界值往往不是控制点算错，
>    而是**我们把 Target 解析错了**（单位、格式），错得越大越像「跳到了结尾」。

> **这些断言做过反向验证**：把 `getCurrentUri()` 临时改成恒返回空串，
> 测试从 95/95 掉到 91/95，失败项正好是那 4 条「有媒体时必须有地址」的断言。
>
> 事件那一段也证伪过：把 `fireInitial()` 改成空操作（也就是退回旧的
> 「只应答不推送」），4 条断言立刻变红（`订阅后收到初始事件`、
> `第二个事件 SEQ 递增到 1`、以及 RenderingControl / ConnectionManager 的初始事件）。
>
> `Seek` 这一段同理：把 `parseTimeToMs` 的失败返回值从 `-1L` 改回 `0L`，
> 协议测试掉到 **152/154** —— 红的正是「非法 Target 必须被忽略」那两条。
> 而 `''` 与 `00:xx:30` 走的是别的分支、**没有被误伤**，说明断言打得准，
> 不是一改就满屏红的粗糙守卫。
>
> 一个只会说「通过」的断言是没有价值的。

**自检脚本自己也要被测**

`tools/dlna-probe.py` 是二夜在真机上唯一能用的排障工具 —— 站在手机那一侧，
把「SSDP 发现 → 抓设备描述 → 核一致性 → 抓 SCPD → 发只读控制指令」整条链路走一遍。

脚本自己报假警报，比没有工具更糟：会把「盒子没问题」误判成「盒子有问题」，
于是去改本来正确的代码。所以 `protocol-test/run.sh` 在跑完驱动之后，
**用同一个靶机再跑一遍这个脚本**，它不过就算失败：

```
── 用同一靶机验证 tools/dlna-probe.py（控制点视角自检）──
  控制点自检：33 / 33 通过
```

这条自查立刻抓到过一个**脚本自身的 bug**：它拿「状态 ≠ `NO_MEDIA_PRESENT`」
当作"有媒体"，而 `STOPPED` 同样是"没有内容"的合法状态（本实现 Stop 时会清空
`CurrentURI`）—— 于是对着一台完全正常的设备报 FAIL。
改成自洽性判据（`NrTracks` 与 `CurrentURI` 必须同进同退）后才对。

同一个道理适用于 `verify-on-device.sh` —— 它才是真机上唯一要跑的那个脚本。
所以还有一个 `verify-device-selftest.sh`：造一个**假 adb** 按真实输出格式回放，
让整条管道（解析参数 → 比 minSdk → 装包 → 起服务 → 从 logcat 抠地址 →
调 `dlna-probe.py` → 判结论）对着同一个真靶机完整跑一遍：

```
── 真机验收脚本自测（假 adb + 真靶机）──
  [PASS] 退出码 0（靶机健康，脚本不该报错）
  [PASS] 从 logcat 正确抠出 IP 与端口
  [PASS] minSdk 闸门没误拦，装包走通
  [PASS] dlna-probe.py 被真的调起
  [PASS] 打印了「真机验收通过」结论
  [PASS] 反面对照：API 13 < minSdk 14 时被拦住（退出码 2，且说清了差多少级）
```

最后一条是**反面对照** —— 不做它，就等于不知道那道 minSdk 闸门到底有没有在工作。

> 这两个自测都做过证伪：把 `dlna-probe.py` 的状态判据改回旧写法、
> 把 `verify-on-device.sh` 的地址抽取弄坏，各自都会变红并传出非零退出码。
> **能红才算测过。**
>
> **反向验证**：把 `ST` 改回写死的旧写法，10 条断言立刻变红。
> 一个只会说"通过"的测试是没有价值的。

**第四道：播放重连策略**

「不断联」是这个项目对用户的核心承诺，而它的实现全在几个数字上：
退避多久、卡死多久算卡死、什么时候该放弃。这些逻辑原本埋在
`MediaPlayerController` 里 —— 那个类要 `android.media.MediaPlayer` 和
`android.view.Surface`，在桌面上跑不起来，于是**零验证**。

现在抽成了 `PlaybackPolicy`：一段纯粹的 Java，零 Android 依赖，
可以直接编译、跑断言，甚至把「反复卡死」这种在真机上要几分钟才走一轮的场景
在毫秒内模拟完。

```bash
./tools/build.sh policy
```

```
播放策略：46 / 46 通过
```

> **抓出的 bug：熔断机制形同虚设。**
>
> 代码注释写着「连续卡死 3 次就停手，避免无限重连反而打断播放」——
> 但 `onPrepared` 里有 `stallCount = 0`。于是：
>
> ```
> 卡死 → 计数 1 → 重连 → prepare 成功 → 计数清零 → 又卡死 → 计数 1 → 重连 → …
> ```
>
> **计数永远到不了 3，熔断永不触发 → 无限重连。承诺和实现正好相反。**
>
> 而这条流是真实存在的：URL 合法、能拉到元数据，但 CDN 限速 / 分段缺失 /
> 码率超出 MT5880 能力，画面就是不动。每一轮重连都会打断一次刚恢复的播放，
> 用户体验比直接报错更差。
>
> 修法：卡死计数**只在播放有实际进展（位置真的前进了）时归零**。
>
> 测试里直接把这个循环跑了一遍：
> ```
> [PASS] 修好之后：会在有限次重连后停手      重连了 3 次后停手
> [PASS] 【反向验证】旧写法 → 确认它无限重连  旧写法在 5000 轮内没有停手
> ```
>
> 另外还发现一处**文档撒谎**：注释里写着「分辨率降级」是稳定性设计之一，
> 但代码里一行都没有 —— 而且 DLNA 推的是手机指定的那个 URL，
> 协议层面就换不了码率，这一条根本做不到。已删除该承诺。

> **同一层里又抓出两个 bug（都是用户报上来的现象）。**
>
> **①「取消投屏后重新投，有时候投不上去」**
>
> `resume()` 原来是
> `if (prepared) start(); else if (currentUrl != null) startInternal();`
> —— 它只看「有没有地址」，完全不知道「正在准备」这回事。
> 而控制点是把 `SetAVTransportURI` 和 `Play` 连着发的（间隔几十毫秒），
> `prepareAsync()` 却要几百毫秒以上，所以 Play 到达时十有八九还在准备中。
> 此时 `startInternal()` 第一句 `releasePlayer()` 就把**正在准备的那个实例释放掉了**。
> 两条指令互相拆台，投得上投不上全看 prepare 快慢 —— 正是「有时候」的来源。
>
> 修法：把「准备中」单独记成一个状态位，Play 到达时**什么都不做**
> （`onPrepared` 回调里本来就会 `start()`，Play 的意图已经被满足了）。
>
> **②「拖完进度条，手机和电视的进度不同步」**
>
> 三个独立机制叠加，而且互不排斥：
>
> - 未 prepare 时 `seekTo` 被**静默丢弃** —— 投屏刚起来就拖进度条，
>   电视一动不动，手机却显示已经拖过去了；
> - `seekTo()` 是**异步**的，位置在真正落地前读到的还是旧值，
>   手机轮询 `GetPositionInfo` 拿到旧位置，进度条被**拉回去**；
> - seek 未落地时位置本来就不动，看门狗会把它判成「卡死」并**触发重连** ——
>   而重连把播放拉回开头。
>
> 修法：引入「seek 待决」状态。未 prepare 时暂存目标、`onPrepared` 后补发；
> 待决期间对外报**目标位置**（手机自己也是按「用户拖到哪」算的，两边就一致了）；
> 看门狗在这段时间**豁免**；收敛判定是「真实位置追上目标（±2 秒）」，
> 外加 15 秒超时兜底 —— 超时就交回真实位置，
> **不能一直报乐观值，那是谎报军情**。

**为什么要额外加一道「源码级不变量守卫」**

上面那个 bug，光靠单元测试**挡不住**回归。实测：把 `stallCount = 0`
挪回 `onPrepared`，`PlaybackPolicy` 本身没变，所以 **46 条断言依然全绿** ——
熔断却整体失效了。

所以 `run.sh` 里额外做了一层源码检查，把不变量钉在代码结构上。
它的判据取「够精确但不脆」：只认结构性事实，不认排版。

```
[PASS] onPrepared 里不出现 stallCount（否则熔断失效）
[PASS] stallCount 清零点只落在 play / stop / checkStall
[PASS] stallCount 清零点不在 onPrepared 里（熔断的命门）
[PASS] 注释里没有未实现的「分辨率降级」承诺
[PASS] pendingRetry 被持有并可取消
[PASS] 块注释定界符配平（/* 与 */ 数量一致）
```

后面几组是这一轮补的，守的都是**没法用网络断言测、又属于静默回归**的东西：

```
[PASS] SUBSCRIBE 走真订阅（调 events.subscribe）
[PASS] UNSUBSCRIBE 走真退订（调 events.unsubscribe）
[PASS] 请求头解析读到了 callback: / sid: / timeout:
[PASS] 先 flush 响应、再推初始事件
[PASS] shutdown() 里关掉了事件线程池
[PASS] DlnaRendererService 实现了 EventSource
[PASS] 事件里的 Volume 取自 getVolume0to100()
[PASS] 音量回读不是硬编码常量
[PASS] 音乐层排在 SurfaceView 之后（否则被画面盖住）
[PASS] 音乐层背景完全不透明（挡住 SurfaceView 的黑）
[PASS] 音乐层只在 MODE_AUDIO 时可见
[PASS] 界面依据 service.isAudioOnly() 判形态
[PASS] 顶部条有隐藏分支（不是恒 VISIBLE）
[PASS] 顶部条会因「暂停」出现
[PASS] 顶部条会因「出错」出现
[PASS] 顶部条会按状态改圆点颜色（出错亮红点，不能是写死的绿点）
[PASS] 布局里有 @+id/overlay_dot（圆点没 id 就只能写死颜色）
[PASS] refresh() 每个 tick 重算顶部条（只写在 applyMode 里就会漏掉暂停）
[PASS] onSeek 里有越界判断（target 超过 duration 就不下发）
[PASS] parseTimeToMs 失败时返回 -1，不是 0
[PASS] parseTimeToMs 处理了小数秒（.F+ 在协议里合法）
[PASS] parseTimeToMs 不用 Double.parseDouble（它接受 NaN，而 (long) NaN == 0）
[PASS] dispatch 的 Seek 分支校验解析结果（不把 -1 当 0 下发）
[PASS] dispatch 区分 Unit（TRACK_NR 的 Target 是曲目号不是时刻）
[PASS] 播放就绪时清掉陈旧的 lastError（否则自愈后顶部条一直挂着报错）
[PASS] resume 用 playAction 决策，而不是「有 URL 就重建」
[PASS] resume 把 preparing 真的传给了 playAction（不能恒传 false）
[PASS] resume 认得 PLAY_WAIT（准备中：什么都不做）
[PASS] MediaPlayerController 有 preparing 状态位
[PASS] startInternal 在 prepareAsync 之前置 preparing
[PASS] onPrepared 里清掉 preparing
[PASS] onError 里也清掉 preparing
[PASS] onPrepared 补发 prepare 期间暂存的 seek
[PASS] seekTo 在未 prepare 时暂存目标，而不是丢弃
[PASS] getPosition 在 seek 待决期间返回目标值（乐观值）
[PASS] getPosition 有「待决结束」的收敛判定
[PASS] checkStall 在 seek 待决期间跳过（否则慢 seek 会被判成卡死）
[PASS] stop() / releasePlayer() 里清掉 seek 待决状态
[PASS] bind 之后有兜底：复查 running 并释放端口
[PASS] isBound() 真的在查绑定状态，不是恒 true
```

**为什么「初始事件必须晚于 200 响应」不进网络断言**：那要求比较两条不同 socket
的到达时刻，而主线程读完响应与事件线程连上回调是**两个独立调度**，
谁先谁后本质上是竞态的 —— 测出来会是「偶尔红」的假信号，比不测更糟。
所以它只钉在源码上（比较 `out.flush()` 与 `events.fireInitial(` 的位置）。

**为什么音乐模式那几条必须钉源码**：把音乐层的背景改成透明、或者把它挪到
`SurfaceView` 前面，代码照样编译、照样运行、日志里一个字都不多 ——
**只是电视又变回一片黑**。而「声音在放、画面全黑」恰恰是用户最容易
误判成「投屏坏了」的现象，也是这一轮专门要修的东西。

> 这几条也全部证伪过：把 `getVolume0to100()` 改回 `return 100;`、
> 把初始事件挪到 `flush()` 之前、把音乐层背景改成
> `@android:color/transparent` 并挪到 `SurfaceView` 之前、把
> `music.setVisibility(...)` 改成恒 `VISIBLE` —— 每一条都精准变红。

**为什么「顶部条不许常驻」必须钉源码**：把 `applyTopBar()` 改回
`overlay.setVisibility(VISIBLE)`，编译、运行、日志全都正常 ——
只是视频画面上又压了一条半透明黑带。而「多了一条带子」这种事，
桌面上跑的任何行为断言都看不见（它只存在于渲染结果里）。

**为什么 `Seek` 那几条也要钉源码**：`return 0` 与 `return -1` 的差别，
在报文层面只体现为「跳回开头」和「什么都不做」—— 两者都是合法的 200 响应，
协议测试**只看得到被调用的值**，看不到调用方拿到 `-1` 之后有没有正确处理。
所以「调用方必须校验」这一半只能钉在源码上，行为那一半交给协议测试。

> 这几条也全部证伪过：把 `parseTimeToMs` 的失败返回值改回 `0L`、
> 删掉 `onSeek` 的越界判断、把 `applyTopBar` 改成恒 `VISIBLE` ——
> 协议测试掉到 152/154、策略测试精准红 3 条，且**没有误伤别的断言**。

**为什么「重投」和「拖拽同步」那几条也必须钉源码**

这两个现象有个共同点：**编译、运行、日志全都正常**。

- 「取消投屏后重新投，有时候投不上去」—— 控制点把 `SetAVTransportURI` 和
  `Play` 连着发（间隔几十毫秒），而 `prepareAsync()` 要几百毫秒以上。
  Play 到达时几乎必然还在准备中，此时若按「有 URL 就 `startInternal()`」处理，
  就会把**正在准备的那个 MediaPlayer 释放掉重建**。两条指令互相拆台，
  谁先谁后全看 prepare 快慢 —— 于是表现成「有时候行、有时候不行」。
- 「拖完进度条两边不同步」—— 三个独立机制叠加，互不排斥：
  (a) 未 prepare 时 seek 被静默丢弃；(b) seek 是异步的，落地前读到的还是旧位置，
  手机轮询 `GetPositionInfo` 会把进度条拉回去；(c) seek 未落地时位置本来就不动，
  看门狗会把它判成「卡死」并触发重连 —— 而重连把播放拉回开头。

> 这一轮证伪了 **8 处**，每处只让**恰好 1 条**断言变红、无连带误伤：
> 把 `resume` 里的 `preparing` 传成常量 `false`、`seekTo` 不再暂存目标、
> `getPosition` 交回旧值、`checkStall` 不再豁免 seek 待决、
> `startInternal` 不再置 `preparing`、`onPrepared` 不再补发暂存 seek、
> `run()` 去掉 bind 后的兜底、`isBound()` 改成恒 `true`。

> 其中**两条守卫是被证伪本身揪出来的**，值得单独记一笔：
> 「`run()` 里有 `if (!running)`」这条断言恒真 —— 因为 `accept` 的 `catch` 里
> 本来就有一句 `if (!running) break;`，把新加的兜底整段删掉，断言**依然是绿的**。
> 同理「有 `isBound()` 方法」只查了方法存不存在，把它改成 `return true;` 也照样绿。
> 现在判据改成了「bind 之后真的关过一次 socket」和「`isBound()` 真的读了 `bound`」。
> **一条只会说「通过」的断言比没有断言更糟 —— 它给人虚假的安全感。**

> 最后一条是踩出来的：一次编辑漏掉了类注释的 `*/`，整个类被注释吞掉，
> 报错指向「第一个字段声明处」，而真实原因在几十行之前。
> 现在 `run.sh` 还会拿 `android.jar` 把 `MediaPlayerController`
> **单独编译一遍**做快速语法检查 —— 这类错误几秒钟就挡住了，不必等 gradle。

> 还有一条是**检查器自己**踩出来的：源码级不变量用正则去匹配源码，
> 结果匹配到了**注释里**的字符串 —— 注释里写着「刻意不用 `Double.parseDouble`」，
> 却被判成「用了 `Double.parseDouble`」，反过来冤枉正确代码。
> 现在所有源码检查都先过一道 `strip_comments()`（字符串字面量保留）。
> **凡是「检查源码」的工具，都要先想一遍「它会不会读到注释」。**

---

## 已知边界

- **乐联（LeLink）协议不支持**。B站、抖音、部分腾讯视频走的是乐播的私有闭源协议，开源界没有实现，无法对接。能收的是标准 DLNA / UPnP 推送。
- **AirPlay 未实现**。iOS 侧目前只能用支持 DLNA 的 App 投。要做 AirPlay 接收需要移植 UxPlay（C/C++，GPLv3），是独立的一大块工作。
- **镜像（Miracast）不做**。老盒子 Wi-Fi Direct 驱动不稳，正是断联根因，不值得修。
- **从未在真机上运行过**。已通过九项桌面核验（编译 / lint `NewApi` 零命中 /
  API 引用 215 项全命中 / DEX 版本 035 / 签名在 API 15 上有效 /
  DLNA 协议 154 项通过 / 播放策略 46 项断言 + 80 条源码级不变量通过 /
  控制点自检脚本 33 项通过 / 真机验收脚本管道自测 6 项通过），
  但真机上的组播收发、MediaPlayer 硬解、断联恢复都还没实测 ——
  `./tools/verify-on-device.sh` 已经就绪，插上盒子跑一条命令即可验。

---

## 真机验收

前面五道闸全是**桌面端**跑的。它们能证明「代码自洽」，证明不了「盒子真的收得到投屏」。
真机上只有三件事必须真机验，而且都验不了于桌面：

1. **SSDP 组播收不收得到** —— 受 `MulticastLock`、网卡选择、路由器 IGMP 影响
2. **MediaPlayer 能不能硬解 MT5880 上的真实码流**
3. **断流自愈策略在真实网络下走不走得通**

一条命令把它们压到一起：

```bash
./tools/verify-on-device.sh                 # USB 连接的盒子
./tools/verify-on-device.sh 192.168.1.9     # 先局域网 adb connect
./tools/verify-on-device.sh 192.168.1.9 --play 'http://x/y.mp4?a=1&b=2'
```

它按顺序做六件事：

| 步 | 做什么 | 为什么这么做 |
|---|---|---|
| ① | 装包前先比 `minSdk` 与设备 API | 否则只会拿到一句 `INSTALL_FAILED_OLDER_SDK`，看不出差多少级 |
| ② | `adb install -r` 覆盖安装 | 老设备上偶发 `ALREADY_EXISTS` |
| ③ | 拉起 `MainActivity`（服务在 `onCreate` 里起） | 顺带把「已安装但从未启动过」这个自启前提解决掉 |
| ④ | **从 logcat 里抠出真实地址** | 服务自己会把 `NetUtil` 实际选中的地址打进日志（`接收端已就绪：… 地址=http://IP:PORT/...`）。这比解析 `netcfg` / `ip addr` 可靠 —— 它反映的正是真正生效的那张网卡 |
| ⑤ | 用这个地址跑 `dlna-probe.py` | 站在手机那一侧把发现链路完整走一遍 |
| ⑥ | 抓 MediaPlayer 相关日志 | Android 4.0 上是 `AwesomePlayer`；出现 error 就该怀疑编码格式了 |

自检没过时它会额外打网络诊断（盒子侧接口、组播锁状态）—— 「手机搜不到设备」
九成就出在这两处。

> 脚本能替你做上面六件事，但有三件替不了，只能你自己拿手机点：
> **画面出不出得来 / 拖进度条会不会卡死 / 拔网线 20 秒再插上会不会自愈。**

---

## 排障

电视上直接能看到诊断信息，不用连电脑：

- **设备名** — 手机投屏列表里应该出现的名字（`聚屏-<型号>`）
- **地址** — 盒子的 IP:端口。手机搜不到时，先确认手机和盒子在同一个网段
- **网络** — 组播实际绑在哪张网卡上（有线优先）。若显示 `(未绑定) 候选: ...`，
  说明组播没绑上，后面列出的是系统里**可用于组播的网卡**（网卡名/IP）。
  候选为空就是这台设备确实没有可用的局域网接口
- **状态** — 等待投屏 / 正在播放 / 已暂停 / **服务未就绪** / 出错
  （`服务未就绪` 单独占一档：这时手机**搜得到设备却投不上去** ——
  SSDP 活着、HTTP 没起来。它是最容易被误判成「手机的问题」的一种故障）

这些都在**等待投屏**的主面板上。播放中顶部那条状态条只在**暂停 / 出错 / 缓冲**时
出现 —— 正常播放时它是隐藏的，那是刻意做的（见「界面三态」）。

播放中出问题就得看 logcat 了，两条最有用的：

```bash
adb logcat | grep -E "MediaPlayerController|DlnaRendererService"
```

- **`Seek：target=…ms, duration=…ms`** —— 每次拖进度条都会打。
  **这两个数必须对着看**：`target` 明显大于 `duration` 就是解析或单位错了，
  服务会**忽略**这次 seek 并补一条 `Seek 目标越界，已忽略`。
  只看到 `target=0` 而控制点明明拖到了中间，那就是 `Target` 的格式没被认出来。
- **`检测到卡死（位置停在 …ms，时长 …ms），第 N 次重连`** —— 看门狗判定卡死。
  连续出现到上限会打 `连续卡死 N 次，停止重连`，那是熔断生效，不是故障。
- **`Play 到达时仍在 prepare，已并入本次准备（不重建播放器）`** ——
  这条是**正常**的，不是故障：控制点把 `SetAVTransportURI` 和 `Play` 连着发，
  Play 到达时准备还没完成。看到它就说明修复生效了 ——
  旧代码在这条路径上会把**正在准备的播放器掐掉重建**，
  投得上投不上全看 prepare 快慢，所以表现成「有时候投不上去」。
- **`seek 请求早于 prepare，已暂存 …ms`** —— 投屏刚起来就拖了进度条，
  请求被暂存、等准备完成后补发。正常应该紧跟一条
  `prepare 完成，补发暂存的 seek …ms`；**只有前者没有后者**，
  说明 prepare 那一步卡住了（去上面找 `播放错误` 或 `重连`）。

遥控器按「重启服务」可以原地重启整个接收端，不用拔电。
