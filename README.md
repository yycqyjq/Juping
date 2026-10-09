# 聚屏（Juping）

把老电视盒子变成投屏接收端。**任何走标准 DLNA / UPnP 的投屏 App** 点「投屏」，画面就上电视 ——
兼容性**按标准判、不按 App 名单判**：标准的**必选动作全部实现**、能力如实声明、出错如实报错
（规范里没做的可选部分也如实列出来），就不再逐个 App 适配，下限是「大多数主流投屏 App 都能用」。
已真机实测的抽样见下方「控制点兼容」。

**目标设备**：Android 4.0.4（API 15）· MediaTek MT5880 · 0.6GB RAM

---

## 下载

直接拿现成的 APK：去 [GitHub Releases](https://github.com/yycqyjq/Juping/releases/latest)
下载 `juping-<版本>-release.apk`，按下方「安装与首次启动」装到盒子上，不需要编译。

和盒子自带投屏的区别：自带的那个走 **Miracast / Wi-Fi Direct** —— 手机和盒子要直连一条
P2P 链路，射频层面受干扰、驱动各家实现不一，掉线是常态。聚屏走 **DLNA（普通局域网
HTTP）**，不碰 Wi-Fi Direct，天然没有这个问题。

---

## 功能一览

- **视频 / 音乐 / 图片投屏**：手机推 URL，盒子用系统 `MediaPlayer` 硬解，手机不转码
- **音乐界面**：封面 + 歌名 + 歌手 + 歌词（控制点给了就显示）；换歌不闪面板；歌单连播
- **断流自愈**：看门狗 + 指数退避重连 + 熔断，见下方「稳定性是怎么保的」
- **扫码网页传文件**：电视空闲时显示二维码，手机扫码上传、本地直接投（[doc/web.md](doc/web.md)）
- **U 盘 APK 安装页**：局域网里让电视列出 U 盘安装包、拉起系统安装器（[doc/web.md](doc/web.md)）
- **开机自启 + 前台服务**：通电即用；设备改名支持多台盒子不混淆（见「排障 · 改名」）
- **`/status` 诊断**：浏览器直接读 JSON 状态，不用连电脑（见「排障」）

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
│   │   ├── RenameReceiver.java      设备改名（adb 广播，全项目唯一 exported 指令面）
│   │   ├── DlnaRendererService.java 前台服务心脏（DLNA + 播放器都挂这儿）
│   │   ├── dlna/
│   │   │   ├── NetUtil.java         网卡选择的唯一出处（SSDP 与 LOCATION 共用）
│   │   │   ├── SsdpResponder.java   组播监听 + 设备回应
│   │   │   ├── EventDispatcher.java GENA 事件订阅与 NOTIFY 推送（自带单线程池保 SEQ 有序）
│   │   │   └── UpnpHttpServer.java  HTTP 服务 + SOAP 控制解析 + SUBSCRIBE 路由
│   │   ├── player/
│   │   │   ├── PlaybackPolicy.java         重连策略：纯逻辑，零 Android 依赖
│   │   │   ├── MediaPlayerController.java  播放 + 看门狗 + 指数退避重连 + 音量状态
│   │   │   └── MediaProxy.java             本地预取缓冲代理（默认关，见已知边界）
│   │   └── web/                     扫码网页传文件（批 1）/ 外接存储装应用（批 3.5）
│   │       ├── MultipartLite.java   流式 multipart 解析：纯逻辑，零 Android 依赖
│   │       ├── LocalStore.java      上传落盘（内部存储）+ 剩余空间 + U 盘挂载点探测 + 防穿越
│   │       ├── WebCastEndpoints.java GET / 页 · POST /upload · POST /cast · POST /delete · GET /files · GET /media
│   │       ├── ApkEntry.java        APK 记录：路径 / 名 / 大小 / 包名 / 应用名 / 版本
│   │       ├── ApkScan.java         外接卷 APK 扫描内核：纯逻辑，零 Android 依赖
│   │       ├── ApkScanner.java      Android 胶水：枚举存储卷 + 取 APK 元数据 + 后台线程
│   │       ├── ApkEndpoints.java    GET /apk 页 · GET /apk/list · POST /apk/install · POST /apk/refresh
│   │       └── WebRouter.java       复合路由：把上传端点与安装包端点串成一个 WebEndpoints
│   └── res/                         布局、配色、字符串、图标、banner
├── doc/                             深度文档（README 拆出的续篇）
│   ├── verification.md          闸门与工程验证记录
│   ├── design-notes.md          界面与播放行为笔记
│   ├── web.md                   扫码上传 / 安装包网页功能
│   └── troubleshooting.md       logcat 日志判读
└── tools/
    ├── build.sh              一键构建 + 出包前核验
    ├── lib.sh                各脚本共用的 JDK 定位（build.sh 与四个 run.sh 都 source 它）
    ├── check_api_compat.py   逐个核验平台 API 引用是否在目标版本里存在
    ├── make_icon.py          生成全部位图资源（纯标准库）
    ├── probe-tv.sh           adb 探测盒子真实硬件信息（只读）
    ├── verify-on-device.sh   一条命令真机验收：装包 → 起服务 → 自检
    ├── dlna-probe.py         控制点视角自检（站在手机那一侧走完整链路）
    ├── apk_info.py           解析 APK 的包名 / minSdk
    ├── check_no_secrets.py   发布前核查：密钥真实值有没有混进被跟踪的文件
    ├── check_gate_counts.py  五道闸门用例总数 ↔ README / AGENTS / doc 一致性
    ├── check_sources.py      无 JDK 环境下的源码结构检查
    ├── check_dex_entrypoints.py  反汇编 dex，核 R8 有没有把框架回调名改坏
    ├── protocol-test/        DLNA 协议层端到端测试（桌面 JVM，不需要真机）
    │   ├── run.sh            编译 → 起服务 → 驱动 → 验证两个自检脚本
    │   ├── drive.py          245 项一致性检查（原始 socket 精确控字节）
    │   ├── ProtocolTestServer.java  在桌面跑真实的 UpnpHttpServer + SsdpResponder
    │   ├── verify-device-selftest.sh  用假 adb 验 verify-on-device.sh 的管道
    │   └── android/util/Log.java    android.util.Log 的桌面替身
    ├── policy-test/          播放重连策略测试（纯逻辑，不需要真机）
    │   ├── run.sh            编译 + 237 项断言 + 512 条源码级守卫
    │   └── PolicyTest.java   237 项断言 + 「卡死→重连→又卡死」「片尾→误判→从头再放」循环模拟
    ├── proxy-test/           本地预取代理字节一致性测试（11 项）
    │   ├── run.sh            编译 → 起源站 → 全量/Range/回拖/EOS/中途重连 逐字节比对
    │   └── ProxyTest.java    JDK 自带 HttpServer 当片源
    └── web-test/             网页逻辑一致性测试（52 项）
        ├── run.sh            编译 → multipart 逐字节/名字编码/流式边界 → APK 扫描内核 → 源码不变量
        ├── WebTest.java      MultipartLite + LocalStore.sanitize 的纯逻辑断言
        ├── ApkScanTest.java  ApkScan 纯逻辑断言（跳过规则/深度/上限/时间预算/取消/去重）
        └── android/          android.util.Log / android.content.Context 桌面替身（只为编得过）
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
./tools/build.sh proxy        # 跑本地预取代理字节一致性（纯 Java，不需要真机）
./tools/build.sh web          # 跑网页上传解析一致性（纯逻辑，不需要真机）
./tools/build.sh dex          # 反汇编 dist/ 里的 release 包，核 R8 有没有改坏入口点
./tools/build.sh clean
```

产物：

```
dist/juping-<版本>-release.apk   ← 装机用这个（已签名）
dist/juping-<版本>-debug.apk     ← 排障用（带 debuggable 标记）
```

`dist` 目标会在归集后**自动跑八道闸**，任何一道不过就报错退出 —— 免得把一个装不上的、点开就崩的、投不进来的、断联后恢复不了的、字节被传坏了、或者带着签名密钥的包交出去：

1. **签名**：以 API 15 为目标验证（`apksigner verify --min-sdk-version 15`）
2. **API 兼容性**：逐个核对 dex 里引用的每个平台成员在目标版本里是否真的存在
3. **dex 入口点**：反汇编 dex，确认 R8 没有把框架回调名改坏（改了 = 装得上、点开就崩）
4. **协议层**：把真实的 UPnP 服务编到桌面 JVM 上，发真实 DLNA 报文核对响应
5. **播放策略**：退避表 / 卡死阈值 / 熔断边界 + 「卡死→重连→又卡死」循环模拟
6. **本地预取代理**：代理吐出的字节流与片源逐字节一致（差一字节就是花屏）
7. **网页上传解析**：multipart 解析与文件名规整的纯逻辑断言（防「传上去了但字节是坏的 / 名字能穿越目录」）
8. **密钥核查**：确认签名密钥的真实值没有混进任何被跟踪的文件（这个仓库是公开的）

如果工具链已在 PATH 里，也可以直接用 wrapper：

```bash
./gradlew assembleRelease
```

工具链需求：

| 组件                 | 版本                             | 位置                                             |
| -------------------- | -------------------------------- | ------------------------------------------------ |
| JDK                  | 17 (Temurin aarch64)             | `~/.android-build/jdk/Contents/Home`             |
| Gradle               | 7.5                              | `~/.android-build/gradle`                        |
| Android SDK          | platform-33 + build-tools 33.0.0 | `~/.android-build/sdk`                           |
| **基线 android.jar** | **API 14 与 15**                 | `~/.android-build/sdk/platforms/android-{14,15}` |

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
unzip -p dist/juping-<版本>-release.apk META-INF/MANIFEST.MF | grep Digest
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
adb install -r dist/juping-<版本>-release.apk
```

局域网 adb 需要盒子侧已经开着网络调试并在监听 5555 —— 零售盒子默认是关的，
所以这条路通常只在 USB 下才通。

### 路径 B：U 盘（最通用，不依赖任何调试通道）

1. 把 `dist/juping-<版本>-release.apk` 拷到 U 盘。**用 FAT32** ——
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
  ls -l dist/juping-<版本>-release.apk   # 记下这个字节数，再和 U 盘里那个比
  # 两个数一致就说明拷完整了。
  # 刻意不写死具体数字 —— 每次重新构建都会变，写死的那份迟早对不上，
  # 反而会让人以为文件拷坏了（这里原来就写着一个过期的字节数）。
  ```

### 路径 C：局域网 HTTP（盒子有浏览器，或能装文件管理器）

在 Mac 上把 `dist/` 挂成静态服务，盒子侧用浏览器或下载器取：

```bash
cd dist && python3 -m http.server 8000
# 盒子浏览器打开 http://<Mac 的 IP>:8000/juping-<版本>-release.apk
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

| 机制                            | 防的是什么                                                     |
| ------------------------------- | -------------------------------------------------------------- |
| **前台服务** + `START_STICKY`   | 被系统/低内存杀手回收                                          |
| **WifiLock** (`FULL_HIGH_PERF`) | 息屏后 Wi-Fi 降频断流                                          |
| **MulticastLock**               | 收不到 SSDP 组播（表现为「手机搜不到设备」）                   |
| **网卡选择要挑有 IPv4 的那张**  | 绑到隧道/蜂窝接口（`utun3` / `rmnet_data0`）会让 SSDP 静默死掉 |
| **网卡依次重试 + 加入后台重试** | 候选列表里第一张 `joinGroup` 失败就换下一张；**全失败也不放弃** —— 加入改到后台带退避重试，直到加入成功才广播 alive |
| **绑定失败带退避重试**          | 开机时 Wi-Fi 还没就绪 → 候选网卡为空 → SSDP 线程永久结束        |
| **端口可用 / 组播就绪解耦**     | 「NIC up + 有 IPv4」**不等于**「此刻组播已就绪」：端口绑上即视为可用（`isPortBound`），组播在后台自愈 —— CI（不转发组播）因此也能跑通协议靶机 |
| **LOCATION 由绑定网卡算出**     | 组播绑 A 网卡、却告诉手机去 B 网卡取描述（搜到了却投不了屏）   |
| **请求体 / 订阅表 / 连接数上限** | 畸形或恶意请求把 0.6GB 内存吃光，连累整个进程（SSDP 一起死）   |
| **看门狗**                      | 播放卡死不动 —— 点播 20s、直播 60s 两档阈值                    |
| **prepare 卡死看门狗**          | prepareAsync 发出后 10s 无任何回调（媒体服务卡死，手机端表现为永久「加载中」）→ 重建播放器自救，连续 2 次到顶即如实报错。原为 30s，视频蓝屏修复（releasePlayer 去 reset 竞态）后降为安全网 |
| **指数退避重连**                | 1s → 2s → 4s → 8s → 16s，最多 5 次                             |
| **卡死熔断**                    | 连续卡死 3 次就停手，避免无限重连反而打断播放                  |
| **旧重连可取消**                | 换新视频时掐掉上一个视频排期的重连，免得把新画面顶掉           |
| **-38 循环防护**                | 错误态/已释放的播放器上任何方法调用都会触发错误回调，造成 ERROR 状态 25 次/秒刷屏 —— 置标志禁调，服务层状态去重兜底 |
| **Auto-Stop**                   | 最后一个订阅者过期且 120s 无控制指令 → 自动停止（手机退出投屏后电视不会一直播；纯投放型控制点不受影响） |

看门狗为什么要两档阈值：点播流的 `getDuration()` 有值，位置 20 秒不动就是真卡了；而 HLS 直播的位置可能长时间不增长甚至恒为 0，用 20 秒判会把正常播放误杀成卡死。

---

## 界面三态

电视上只有三种形态，靠播放状态自动切换：

| 形态         | 什么时候             | 显示什么                                         |
| ------------ | -------------------- | ------------------------------------------------ |
| **等待投屏** | 没有内容             | 左列：引导卡片 + 设备信息（名字 / 地址 / 网络 / 状态 / 版本，外加系统·处理器·内存·存储四格）；右列：通高二维码与使用说明 |
| **视频播放** | 有内容，且含画面     | **纯画面，全屏，顶上什么都没有**                 |
| **音乐播放** | 有内容，但**纯音频** | 音符卡片：封面 + **歌名（最大）** + 歌手 + 进度    |

各形态背后的行为取舍（状态条为什么按状态出现、进度为什么分两档、音乐层为什么
单独画、换歌为什么不闪面板）见 [doc/design-notes.md](doc/design-notes.md)。

---

## 兼容性红线

代码按 android-33 编译，但**运行在 API 15**。以下都是「编译得过、真机上崩」的坑，已在 `app/build.gradle` 的 `lint` 块里显式关闭并注明理由：

| 不能用                                | 因为                                  |
| ------------------------------------- | ------------------------------------- |
| `FLAG_IMMUTABLE`（PendingIntent）     | API 23 才有 → `NoSuchFieldError`      |
| `layout_marginStart` / `paddingStart` | API 17 才有 → 只能继续用 `Left/Right` |
| `MediaCodec`                          | API 16 才有 —— 本项目全程不碰它       |
| AndroidX 任何组件                     | 普遍要求 minSdk 19+ → 直接编译不过    |

光靠编译过是不够的 —— 五道深度验证与十二轮工程验证记录（每个 bug 怎么被抓出来、
每条守卫怎么证伪过）见 [doc/verification.md](doc/verification.md)。

---

## 控制点兼容：按标准判，不按 App 名单判

兼容的目标不是「适配了哪些 App」，而是**覆盖大多数遵循标准的控制点**。做法是：三个服务
（`AVTransport` / `RenderingControl` / `ConnectionManager`）的**必选动作全部实现**、
SCPD 如实声明、控制点发的动作如实响应 —— 做不到的如实报错（401 / 701），
而不是回一个语法合法的空壳 200。任何标准 DLNA 控制点接进来就能用。

正因如此，本项目的纪律是**不给单个 App 写特例**：连「按品牌预置怪癖档案」的机制都
主动搁置了，理由是它必然滑向「给每个 App 写特例」的维护债。

**规范里有、我们没做或换了形态的，如实列在下面**（不是漏，是取舍，都记了理由）：

| 项 | 规范里的地位 | 我们的取舍 |
| --- | --- | --- |
| AVTransport 事件里的**变化量**语义 | 规范说 `LastChange` 装「变化了的」变量 | 我们每次推**全量**事件化变量。控制点按变量逐项合并，多给已知项没有副作用；而「订阅即推全量」本就是规范硬要求，两条路径合一反而少一处分支 |
| `GetVolumeDB` / `SetVolumeDB` / `GetVolumeDBRange` | 可选动作 | 盒子的音量本来就是个相对值，dB 通道没有真实依据；走 dB 的控制点会拿到 401（取证埋点已留这条判据） |
| `Record*` 录制类动作 | 可选动作 | 只做播放侧，没有录制能力 |
| RenderingControl 的视频类调节（亮度 / 对比度 / 锐度…） | 可选动作 | 投屏不碰画质参数；音量 / 静音才是必选集，已全 |

下面这张表**不是白名单，是抽样证据** —— 拿几个真实 App 打样，证明上面那条标准实现
确实跑得通，并留下踩过的坑（每一条都已经固化成闸门里的守卫）。**没列到的 App 不等于
不能用**；确切说，凡是不走私有协议的，都不该需要我们为它改一行代码。

| 实测样本 | 结论 | 打样时暴露的坑（已固化） |
| --- | --- | --- |
| 腾讯视频 | ✅ 真机实测 | 拖进度条会**重发 `SetAVTransportURI`**（同地址）→ 幂等行为就是按它校准的 |
| 网易云音乐 | ✅ 真机实测 | 它只监听广播、不主动搜索 → 当初「搜不到」是 SSDP 未主动广播所致（见 [doc/verification.md](doc/verification.md) 第九组 ①） |
| 芒果 TV | ✅ 真机实测 | 走 Cling：**`Play` 缺 `in:Speed` 声明**会让它连 Play 都发不出来（能暂停、不能继续播放）。协议 13.2 已加守卫 |
| B站 / 抖音 | ⚠️ 部分内容收不到 | 走乐联（LeLink）私有协议的部分不在 DLNA 路径上，属「已知边界」，不是 bug |
| BubbleUPnP | ☐ 待打样 | 事件已是规范的 `LastChange` 形态（见上表）；歌单连播（`SetNextAVTransportURI`）已支持（0.1.7） |

> 真机实测 = 在目标盒子（MT5880 / Android 4.0.4）上用真实 App 走通投屏全链路。
> **判决标准始终是协议，不是名单**：桌面协议 245 项通过证明「协议自洽」，
> 上面的样本证明「标准实现能被真实控制点吃下去」。样本会一直很少，
> 但每一类问题（重发 SetURI / 只收广播 / 参数校验 …）都会变成守卫，而不是变成特例。

新问题出现时的分诊顺序（照着走，别急着为某个 App 改代码）：

1. **先看是不是私有协议** —— 乐联 / AirPlay / 自家发现方式，属已知边界（见下节）。
2. **再查是不是标准实现的缺口** —— 跑桌面协议 245 项；投屏中的现场看 `/status` 诊断页
   的 `currentUri` 与控制指令日志。
3. **还查不出来就抓包** —— 手机侧按 `tcp port <实际端口>` 过滤，交叉验证我们回的报文
   对方认不认（这是唯一能证明这一点的手段）。

---

## 已知边界

- **乐联（LeLink）协议不支持**。B站、抖音、部分腾讯视频走的是乐播的私有闭源协议，开源界没有实现，无法对接。能收的是标准 DLNA / UPnP 推送。
- **AirPlay 未实现**。iOS 侧目前只能用支持 DLNA 的 App 投。要做 AirPlay 接收需要移植 UxPlay（C/C++，GPLv3），是独立的一大块工作。
- **镜像（Miracast）不做**。老盒子 Wi-Fi Direct 驱动不稳，正是断联根因，不值得修。
- **图片投屏已支持**。`MediaPlayer` 本身不解静态图，所以图片走的是**独立通道**：
  `kindOf()` 认出 `imageItem` 后不进 `MediaPlayer`（否则必然 `error`、全黑），
  界面切到 `MODE_IMAGE`，用 `BitmapFactory`（`inJustDecodeBounds` 量尺寸 +
  `inSampleSize` 降采样防 OOM，解码完 `recycle()`）画在 `ImageView` 上。
  `SINK_PROTOCOL_INFO` 因此**重新声明**了 `image/jpeg` / `image/png` ——
  这份清单是控制点判断"能不能推给我"的唯一依据，声明与实现必须一起变。
- **事件是 AVTransport:1 的规范形态（`LastChange`）**。SCPD 里只有
  `LastChange` 声明为可事件化，`TransportState` / `TransportStatus` /
  `CurrentTrackURI` / `CurrentTrackDuration` / `RelativeTimePosition` 都是
  `sendEvents="no"`，内容以一段 AVT 命名空间（`…metadata-1-0/AVT/`）的
  XML 文档塞在 `LastChange` 的**值**里 —— 这才是 Cling 系控制点
  （芒果 TV 实测）认的写法。早先的「逐变量推送」会让它收不到状态更新，
  退回轮询 `GetPositionInfo`，表现是进度条没那么跟手。
- **真机已经跑过两轮，但覆盖面还很窄**。2026-09-29 在目标盒子（MT5880）上
  实测一轮，暴露并修掉三个基础体验问题（见 [doc/verification.md](doc/verification.md) 第九组）；
  2026-09-30 在第二台真机（海信 Vision-TV / MTK / Android 4.0.4）上实测
  一轮，暴露并修掉：视频误判音频、进度条冻死在 Seek 点、-38 错误刷屏
  死循环、切歌后媒体服务卡死（全部见 [doc/troubleshooting.md](doc/troubleshooting.md)）。
  桌面核验现在是十六项全绿：编译 / lint `NewApi` 零命中 /
  API 引用 412 项（release 419 项）全命中 / DEX 版本 035 / 签名在 API 15 上有效 /
  DLNA 协议 245 项通过 / 播放策略 237 项断言 + 512 条源码级守卫通过 /
  断言/守卫计数与 README、AGENTS、doc 锚点一致（`check_gate_counts.py`，覆盖协议/代理/网页/probe/策略五道）/
  本地预取代理字节一致性 11 项通过 / 网页逻辑一致性 52 项通过 / R8 dex 入口点 47 项通过 /
  控制点自检脚本 33 或 34 项通过（组播回退分支所致，均为合法值）/
  真机验收脚本管道自测 6 项通过 / 密钥核查干净 /
  工具链脚本的变量名边界检查通过（UTF-8 locale 下不再崩）/
  破坏性证伪 50+ 处（每处都只让预期的那几条变红）。
  但**长时间稳定性**（连续投几小时）、**多控制点同时操作**、
  **各种编码格式的硬解**都还没验证过 —— `./tools/verify-on-device.sh`
  一条命令可以跑完基础验收。
- **本地预取缓冲代理（MediaProxy）已实现但默认关**。桌面字节一致性
  11 项全过，但海信自研播放器栈（CmpbPlayer）连 127.0.0.1 代理后
  立即断开，prepare 卡死，降级直连的重试也异常挂起 —— 厂商栈是黑盒，
  根因未明前默认关（`PlaybackPolicy.PROXY_ENABLED`），0.1.7 发布版
  走已验证可靠的直连路径。

---

## 真机验收

前面这些闸门全是**桌面端**跑的。它们能证明「代码自洽」，证明不了「盒子真的收得到投屏」。
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

| 步  | 做什么                                        | 为什么这么做                                                                                                                                                     |
| --- | --------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ①   | 装包前先比 `minSdk` 与设备 API                | 否则只会拿到一句 `INSTALL_FAILED_OLDER_SDK`，看不出差多少级                                                                                                      |
| ②   | `adb install -r` 覆盖安装                     | 老设备上偶发 `ALREADY_EXISTS`                                                                                                                                    |
| ③   | 拉起 `MainActivity`（服务在 `onCreate` 里起） | 顺带把「已安装但从未启动过」这个自启前提解决掉                                                                                                                   |
| ④   | **从 logcat 里抠出真实地址**                  | 服务自己会把 `NetUtil` 实际选中的地址打进日志（`接收端已就绪：… 地址=http://IP:PORT/...`）。这比解析 `netcfg` / `ip addr` 可靠 —— 它反映的正是真正生效的那张网卡 |
| ⑤   | 用这个地址跑 `dlna-probe.py`                  | 站在手机那一侧把发现链路完整走一遍                                                                                                                               |
| ⑥   | 抓 MediaPlayer 相关日志                       | Android 4.0 上是 `AwesomePlayer`；出现 error 就该怀疑编码格式了                                                                                                  |

自检没过时它会额外打网络诊断（盒子侧接口、组播锁状态）—— 「手机搜不到设备」
九成就出在这两处。

> 脚本能替你做上面六件事，但有三件替不了，只能你自己拿手机点：
> **画面出不出得来 / 拖进度条会不会卡死 / 拔网线 20 秒再插上会不会自愈。**

---

## 排障

**最快的一招：浏览器打开 `http://<电视IP>:49152/status`** —— 直接读 JSON 状态
（设备名 / 播放状态 / 位置 / 时长 / 当前 URI / 版本 / 订阅数 / 开机时长），
手机浏览器就行，不需要连电脑。

> `49152` 是**首选**端口。它被别的进程占了（老电视上厂家自带的 DLNA 栈很可能也占它）
> 会自动**上移**到相邻端口，此时界面与 `/status` 里 `httpPort` 显示的是**实际**
> 监听端口 —— 照着显示的那个地址访问即可。回退也会同步到 LOCATION，
> 手机拿到的描述地址永远指向真正在听的端口。

**扫码网页传文件 / 外接存储装应用**的完整端点表、加固与真机注意项见
[doc/web.md](doc/web.md)。

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

播放中出问题要看 logcat —— 每类日志说明什么、哪些是正常路径别当故障，逐条写在
[doc/troubleshooting.md](doc/troubleshooting.md)；「手机进度条不跟」的动态开日志方法也在那篇里。

遥控器按「重启服务」可以原地重启整个接收端，不用拔电。

### 改名

家里有多台盒子时，设备列表里两个「聚屏-MT5880」分不清谁是谁。改名走 adb
（电视上没有可靠的输入法，弹输入框是给用户添堵）：

```bash
adb shell am broadcast -a com.juping.cast.APPLY_RENAME --es name "客厅盒子"
adb shell am broadcast -a com.juping.cast.APPLY_RENAME --es name ""   # 恢复默认名
```

名字立即生效（服务原地重建两条链路），存盘持久，重启、开机自启后不变；
改名不影响 UUID，手机端对这台设备的记忆不会断。名字会被剔除控制字符、
限长 32 字符——这是全项目唯一的 exported 指令面，入口处就该把明显非法
的输入拒掉。

---

## 文档地图

| 文档 | 讲什么 | 给谁看 |
| --- | --- | --- |
| 本文 | 下载、安装、编译、行为承诺、已知边界、快速排障 | 使用者 / 评估者 |
| [doc/verification.md](doc/verification.md) | 五道深度验证 + 十二轮工程验证记录（bug 与证伪） | 想复核质量的人 |
| [doc/design-notes.md](doc/design-notes.md) | 界面与播放行为笔记（为什么长这样） | 好奇的维护者 |
| [doc/web.md](doc/web.md) | 扫码上传页 / 安装包页的端点与加固 | 用网页功能的人 |
| [doc/troubleshooting.md](doc/troubleshooting.md) | logcat 日志逐条判读 | 盒子出问题的人 |
| [AGENTS.md](AGENTS.md) | 面向 AI Agent 的项目速查手册 | 改代码的 Agent / 开发者 |

---

## 许可证

[MIT](LICENSE) —— 改、打包、再分发、商用都行，保留版权声明与许可声明即可。
