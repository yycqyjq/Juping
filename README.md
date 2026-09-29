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
│   │   │   ├── NetUtil.java         网卡选择的唯一出处（SSDP 与 LOCATION 共用）
│   │   │   ├── SsdpResponder.java   组播监听 + 设备回应
│   │   │   └── UpnpHttpServer.java  HTTP 服务 + SOAP 控制解析
│   │   └── player/
│   │       ├── PlaybackPolicy.java         重连策略：纯逻辑，零 Android 依赖
│   │       └── MediaPlayerController.java  播放 + 看门狗 + 指数退避重连
│   └── res/                         布局、配色、字符串、图标、banner
└── tools/
    ├── build.sh              一键构建 + 出包前核验
    ├── check_api_compat.py   逐个核验平台 API 引用是否在目标版本里存在
    ├── make_icon.py          生成全部位图资源（纯标准库）
    ├── probe-tv.sh           adb 探测盒子真实硬件信息（只读）
    ├── verify-on-device.sh   一条命令真机验收：装包 → 起服务 → 自检
    ├── dlna-probe.py         控制点视角自检（站在手机那一侧走完整链路）
    ├── apk_info.py           解析 APK 的包名 / minSdk
    ├── check_sources.py      无 JDK 环境下的源码结构检查
    ├── protocol-test/        DLNA 协议层端到端测试（桌面 JVM，不需要真机）
    │   ├── run.sh            编译 → 起服务 → 驱动 → 用同一靶机验证探测脚本
    │   ├── drive.py          95 项一致性检查（原始 socket 精确控字节）
    │   ├── ProtocolTestServer.java  在桌面跑真实的 UpnpHttpServer + SsdpResponder
    │   └── android/util/Log.java    android.util.Log 的桌面替身
    └── policy-test/          播放重连策略测试（纯逻辑，不需要真机）
        ├── run.sh            编译 + 断言 + 源码不变量守卫
        └── PolicyTest.java   26 项断言 + 「卡死→重连→又卡死」循环模拟
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
dist/juping-0.1.0-release.apk   ← 装机用这个（47K，已签名）
dist/juping-0.1.0-debug.apk     ← 排障用（74K，带 debuggable 标记）
```

`dist` 目标会在归集后**自动跑四道闸**，任何一道不过就报错退出 —— 免得把一个装不上的、投不进来的、或者断联后恢复不了的包交出去：

1. **签名**：以 API 15 为目标验证（`apksigner verify --min-sdk-version 15`）
2. **API 兼容性**：逐个核对 dex 里引用的每个平台成员在目标版本里是否真的存在
3. **协议层**：把真实的 UPnP 服务编到桌面 JVM 上，发真实 DLNA 报文核对响应
4. **播放策略**：退避表 / 卡死阈值 / 熔断边界 + 「卡死→重连→又卡死」循环模拟

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
| **网卡选择要挑有 IPv4 的那张** | 绑到隧道/蜂窝接口（`utun3` / `rmnet_data0`）会让 SSDP 静默死掉 |
| **网卡依次重试** | 候选列表里第一张 `joinGroup` 失败就换下一张，不一次就放弃 |
| **看门狗** | 播放卡死不动 —— 点播 20s、直播 60s 两档阈值 |
| **指数退避重连** | 1s → 2s → 4s → 8s → 16s，最多 5 次 |
| **卡死熔断** | 连续卡死 3 次就停手，避免无限重连反而打断播放 |
| **旧重连可取消** | 换新视频时掐掉上一个视频排期的重连，免得把新画面顶掉 |

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

### 四道独立的验证

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
被引用的平台类 55 个 · 方法 178 个 · 字段 3 个
结论：181 个平台引用全部命中，无 API 越界。
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
协议一致性：95 / 95 通过
```

覆盖两大故障场景 —— **「手机搜不到设备」和「投屏没反应」**：

| 段 | 查什么 |
|---|---|
| 1–2 | 设备描述 XML、三个服务的 SCPD 是否可取且合法 |
| 3–5 | SOAP 控制指令、`SetAVTransportURI` 中文元数据、播放状态机 |
| **6** | **回读契约**：`CurrentURI` / `TrackURI` / `NrTracks` 三者必须自洽，带 `&` 的地址必须能原样回读 |
| 7 | 健壮性：未知路径 / 缺失头 / 畸形报文后服务是否还活着 |
| **8** | **SSDP 发现**：`ssdp:all` 要回全 6 个搜索目标；每个 ST 必须原样回、USN 格式正确；无关搜索与 NOTIFY 不能应答 |
| **9** | **发现链路闭环**：顺着 LOCATION 抓 device.xml → 校验 UDN/deviceType 与 SSDP 一致 → 逐个抓 SCPDURL → 每个 controlURL 可达 |
| 10 | 请求行变体：绝对形式（`GET http://host/path`）、多余空格、带查询串 |

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

> **这些断言做过反向验证**：把 `getCurrentUri()` 临时改成恒返回空串，
> 测试从 95/95 掉到 91/95，失败项正好是那 4 条"有媒体时必须有地址"的断言。
> 一个只会说"通过"的断言是没有价值的。

**自检脚本自己也要被测**

`tools/dlna-probe.py` 是二夜在真机上唯一能用的排障工具 —— 站在手机那一侧，
把「SSDP 发现 → 抓设备描述 → 核一致性 → 抓 SCPD → 发只读控制指令」整条链路走一遍。

脚本自己报假警报，比没有工具更糟：会把「盒子没问题」误判成「盒子有问题」，
于是去改本来正确的代码。所以 `protocol-test/run.sh` 在跑完驱动之后，
**用同一个靶机再跑一遍这个脚本**，它不过就算失败：

```
── 用同一靶机验证 tools/dlna-probe.py（控制点视角自检）──
  控制点自检：32 / 32 通过
```

这条自查立刻抓到过一个**脚本自身的 bug**：它拿「状态 ≠ `NO_MEDIA_PRESENT`」
当作"有媒体"，而 `STOPPED` 同样是"没有内容"的合法状态（本实现 Stop 时会清空
`CurrentURI`）—— 于是对着一台完全正常的设备报 FAIL。
改成自洽性判据（`NrTracks` 与 `CurrentURI` 必须同进同退）后才对。
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
播放策略：26 / 26 通过
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

**为什么要额外加一道「源码级不变量守卫」**

上面那个 bug，光靠单元测试**挡不住**回归。实测：把 `stallCount = 0`
挪回 `onPrepared`，`PlaybackPolicy` 本身没变，所以 **26 条断言依然全绿** ——
熔断却整体失效了。

所以 `run.sh` 里额外做了一层源码检查，把不变量钉在代码结构上：

```
[PASS] onPrepared 里不出现 stallCount（否则熔断失效）
[PASS] stallCount 清零点只落在 play / stop / checkStall
[PASS] stallCount 清零点不在 onPrepared 里（熔断的命门）
[PASS] 注释里没有未实现的「分辨率降级」承诺
[PASS] pendingRetry 被持有并可取消
[PASS] 块注释定界符配平（/* 与 */ 数量一致）
```

> 最后一条是踩出来的：一次编辑漏掉了类注释的 `*/`，整个类被注释吞掉，
> 报错指向「第一个字段声明处」，而真实原因在几十行之前。
> 现在 `run.sh` 还会拿 `android.jar` 把 `MediaPlayerController`
> **单独编译一遍**做快速语法检查 —— 这类错误几秒钟就挡住了，不必等 gradle。

---

## 已知边界

- **乐联（LeLink）协议不支持**。B站、抖音、部分腾讯视频走的是乐播的私有闭源协议，开源界没有实现，无法对接。能收的是标准 DLNA / UPnP 推送。
- **AirPlay 未实现**。iOS 侧目前只能用支持 DLNA 的 App 投。要做 AirPlay 接收需要移植 UxPlay（C/C++，GPLv3），是独立的一大块工作。
- **镜像（Miracast）不做**。老盒子 Wi-Fi Direct 驱动不稳，正是断联根因，不值得修。
- **从未在真机上运行过**。已通过八项桌面核验（编译 / lint `NewApi` 零命中 /
  API 引用 181 项全命中 / DEX 版本 035 / 签名在 API 15 上有效 /
  DLNA 协议 95 项通过 / 播放策略 26 项通过 / 自检脚本 32 项通过），
  但真机上的组播收发、MediaPlayer 硬解、断联恢复都还没实测 ——
  `./tools/verify-on-device.sh` 已经就绪，插上盒子跑一条命令即可验。

---

## 真机验收

前面四道闸全是**桌面端**跑的。它们能证明「代码自洽」，证明不了「盒子真的收得到投屏」。
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
- **状态** — 等待投屏 / 正在播放 / 已暂停 / 出错

遥控器按「重启服务」可以原地重启整个接收端，不用拔电。
