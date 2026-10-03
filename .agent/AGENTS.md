# AGENTS.md — 聚屏（Juping）Agent 协作手册

> 面向 AI Agent 的项目速查手册。用户文档看 `README.md`，当前任务与状态看
> `.agent/todo.md`，历史方案看 `.agent/optimization-report.md` /
> `modification-plan.md`。**改代码前必读本文的第 4、6 节。**

## 1. 项目速览

Android DLNA 投屏接收端（DMR），目标设备是 **2012 年的老电视/盒子**
（实测主力机：海信 Vision-TV，MTK 芯片，Android 4.0.4，API 15，0.6GB 内存）。
零第三方依赖，minSdk 14，语言全中文注释。

**资源稀缺排序（实测结论）**：固件媒体栈质量 > 硬件性能 > 带宽（充裕）。
优化方向是绕固件坑，不是网络。

## 2. 常用命令

```bash
cd /Users/yjq/Desktop/Juping   # 所有命令都从这里出发

# ── 构建（八道闸门全跑，绿了才算完）──
./tools/build.sh dist          # debug+release 双包 + 全部核验 → dist/

# ── 单独闸门 ──
./tools/build.sh proxy         # 本地预取代理字节一致性（11 项）
./tools/build.sh web           # 网页逻辑一致性（52 项）
./tools/build.sh lint          # lint
./tools/build.sh clean         # 清理

# ── 真机（adb over Wi-Fi）──
ADB=~/$.android-build/sdk/platform-tools/adb ./tools/verify-on-device.sh 192.168.1.8
# 或手工：adb connect 192.168.1.8:5555 → install -r dist/*.apk → am startservice

# ── 控制点视角自检（不需要真机）──
python3 tools/dlna-probe.py 192.168.1.8     # 真机；桌面跑见 protocol-test

# ── 运行中诊断 ──
curl http://<电视IP>:49152/status           # JSON 状态页（0.1.7+）
adb shell setprop log.tag.UpnpHttpServer DEBUG && adb logcat | grep UpnpHttpServer
adb shell am broadcast -a com.juping.cast.APPLY_RENAME --es name "新名字"   # 改名
```

**git 注意**：`.git/index.lock` 会被外部进程反复重建，提交必须
`rm -f .git/index.lock && git add -A && git commit` 同一条命令链。

## 3. 代码组件地图

```
app/src/main/java/com/juping/cast/
├── DlnaRendererService.java   前台服务心脏：装配全部组件、Auto-Stop、
│                              改名广播处理、/status 数据源、30s 自检看门狗
├── UpnpHttpServer.java        HTTP+SOAP+SSCPD+GENA 订阅：HTTP 端口首选 49152、
│                              被占自动上移（getPort() 是端口唯一出处），
│                              /status 诊断页，lastControlAt（Auto-Stop 判据）
├── SsdpResponder.java         SSDP 组播应答；单播时灵时不灵（海信自带服务
│                              抢 1900），probe 已做组播回退；端口可用与
│                              组播就绪解耦（joinGroup 失败后台带退避重试）
├── EventDispatcher.java       GENA 订阅表+NOTIFY 投递；订阅数=Auto-Stop 判据
├── NetUtil.java               网卡选择唯一出处
├── DidlLite.java              DIDL-Lite 元数据解析（title/artist/class/album/
│                              albumArtURI 多尺寸择优/lyrics 机会性；取不到返回空串）
├── MainActivity.java          界面：前台唤醒/播完延迟退后台（MTK 蓝屏规避）
│                              空闲时画二维码（扫码进上传页，批 2）：按地址缓存、
│                              换图 recycle 旧位图、生成失败整块 GONE
│                              音乐态（批 3.6）：歌名放大（32sp 主位）+ 封面 +
│                              歌手/歌词行；封面走 albumArtURI（后台线程 / 流式落盘
│                              ≤16MB / 按 View 尺寸解码 / 三字段缓存 / 代次作废 /
│                              recycle），取不到一律退回 ic_music（不留白块）
│                              换歌宽限（批 3.7）：控制点 Stop→Set（~230ms）之间保持
│                              音频态 + 冻结卡片，不闪待机面板；只对音频（视频宽限会
│                              闪蓝屏），遥控器返回键置 userInitiatedStop 不吃宽限
├── QrRenderer.java            二维码位图绘制：QrCode → 逐格 int[] → 一次 setPixels
│                              （替代上游 toImage()，Android 无 java.awt/ImageIO）
├── RenameReceiver.java        adb 改名广播入口（唯一 exported 指令面）
├── BootReceiver.java          开机自启
├── player/
│   ├── MediaPlayerController.java  播放+看门狗+重连+位置外推+prepare 卡死
│   │                               重建+nativePlayerDead 守卫（见 §6）
│   ├── MediaProxy.java             本地预取缓冲代理（PROXY_ENABLED 默认关：
│   │                               海信 CmpbPlayer 黑盒，详见 todo.md）
│   └── PlaybackPolicy.java         纯逻辑策略/阈值常量（桌面可测）
└── web/                       扫码网页传文件（批 1）/ 外接存储装应用（批 3.5）
    ├── MultipartLite.java     流式 multipart 解析（纯逻辑、零 Android 依赖）
    ├── LocalStore.java        落盘根=getFilesDir()/uploads（**内部优先**，真机
    │                          实测「外部存储=U 盘」已证伪原「外部优先」）；
    │                          剩余空间；/proc/mounts 探 U 盘挂载点；防穿越；
    │                          isUnder(root,target) 共享防穿越 helper（批 3/3.5）
    ├── WebCastEndpoints.java  GET / 页(多文件/文件夹降级/进度/删除) ·
    │                           POST /upload(空间预检 411/413/507) ·
    │                           POST /cast · POST /delete · GET /files ·
    │                           GET /media(HTTP+Range+HEAD)
    │                           ※ 媒体**不走 file://**：mediaserver 是另一进程、
    │                             穿不进 drwx------（真机证伪，见计划 §5.2.1）
    │                           ※ 这里的 WebEndpoints/WebResponse 接口定义在
    │                             UpnpHttpServer 内（协议闸门编译白名单不含 web 包）
    ├── ApkEntry.java          APK 记录：路径/名/大小/包名/应用名/版本
    ├── ApkScan.java           外接卷 APK 扫描内核（纯逻辑、零 Android 依赖）：
    │                          迭代遍历、深度≤8、条数≤300、时间≤8s、跳过系统/隐藏目录
    ├── ApkScanner.java        Android 胶水：枚举存储卷 + getPackageArchiveInfo 补元数据
    │                          + 专用线程扫描 + 代次取消 + 结果缓存
    ├── ApkEndpoints.java      GET /apk 页 · GET /apk/list · POST /apk/install ·
    │                          POST /apk/refresh（安装=唯一有副作用路由；白名单+isUnder 两道加固）
    └── WebRouter.java         复合路由：串 WebCastEndpoints + ApkEndpoints → 一个 WebEndpoints
app/src/main/java/io/nayuki/qrcodegen/            ← **唯一的第三方源码**
├── QrCode.java · QrSegment.java · BitBuffer.java · DataTooLongException.java
│                              Nayuki QR Code generator 1.8.0
│                              （MIT；纯源码内嵌、无二进制，是「零依赖」铁律的
│                                **明示例外**，见 §6。只用它的编码核心；
│                                上游 1.7.0 起 DataTooLongException 是独立顶层类，
│                                少搬它会编译不过。API 15 适配只有两处：
│                                requireNonNull → 显式判空、UTF-8 取字节走 Charset。
│                                绘制不用上游 toImage()，改由 QrRenderer 逐格画）
tools/
├── build.sh              一键构建+八道闸门（apk/api/dex/protocol/policy/proxy/web/secrets）
├── lib.sh                各脚本共用的 JDK 定位（build.sh 与四个 run.sh 都 source 它）
├── verify-on-device.sh   真机一条命令验收
├── probe-tv.sh           电视硬件信息探测（只读）
├── dlna-probe.py         控制点视角自检（单播→组播回退→直连降级，33/34 项）
├── check_api_compat.py   平台 API 引用逐个核对（260+ 命中）
├── check_dex_entrypoints.py  R8 后 dex 入口点核查
├── check_sources.py      无 JDK 环境的源码结构检查
├── check_no_secrets.py   密钥泄漏核查
├── check_gate_counts.py  五道闸门用例总数 ↔ README/AGENTS 文档 一致性
├── apk_info.py           APK 包名/minSdk 解析
├── make_icon.py          位图资源生成（纯标准库）
├── protocol-test/        DLNA 协议一致性 245 项（桌面 JVM + 真实协议栈桩）
├── policy-test/          播放策略 112 断言 + 465 源码级守卫
├── proxy-test/           MediaProxy 字节一致性 11 项
└── web-test/             MultipartLite + sanitize + ApkScan 网页逻辑一致性（52 项）
```

## 4. 测试与闸门矩阵

`./tools/build.sh dist` 依次跑，**任何一门红都不能提交**：

| 闸门 | 内容 | 断言数 |
|------|------|--------|
| verify_apk | 签名/minSdk | 每包 |
| verify_api | 平台 API 引用逐个核对（目标 API 15/33） | 412/419 |
| verify_dex | R8 后框架回调/Thread 子类/协议常量存活 | 全量 |
| verify_protocol | DLNA 协议一致性（drive.py，期望 245/245） | 245 |
| ↳ 内含 probe | 控制点自检（**33 或 34 双态**：组播回退分支） | 33/34 |
| verify_policy | 播放策略 112 断言 + 465 源码级守卫 | 87+ |
| ↳ 内含计数 | 文档里的用例总数 ↔ 闸门期望值（`check_gate_counts.py`，覆盖协议/代理/网页/probe/策略） | 一致性 |
| verify_proxy | MediaProxy 字节一致性（全量/Range/回拖/EOS/中途重连） | 11 |
| verify_web | multipart 解析逐字节一致 / 名字编码 / APK 扫描内核 / 上传页与安装页零外链 | 52 |
| verify_secrets | 密钥泄漏 | 零命中 |

**总数守卫是特性**：协议 245、probe 33/34 双态（组播回退分支）、策略 78、网页逻辑一致性 52 项。
有意增删断言后必须同步 build.sh / run.sh 里的期望值。

**计数单一事实来源**：策略的断言/守卫数，以及协议/proxy/web/probe 的用例总数，
手写在 `README.md` 与 `.agent/AGENTS.md` 里（措辞「N 项断言」/「N 条源码级守卫」/
「N 项一致性」等，量词可有可无）。`verify_policy` 末尾用
`tools/check_gate_counts.py` 拿这两个文档和闸门里的期望值核对，对不上就红 ——
规范值的唯一出处仍是 `build.sh` / `protocol-test/run.sh` 里那句比较字符串，
文档跟不上就报出来。再不用靠人肉同步多处手写数字（那必然漂，T9 就是被 QA
抓到的；README 里那句样本输出写的 `协议一致性：219 / 219` 也是这么留下的）。
`AGENTS.md` 因此**必须进版本库**（见根目录 `.gitignore` 里那条例外）。

## 5. 真机调试手册（Hisense Vision-TV 实测坑）

**排障流程**：`adb connect 192.168.1.8:5555` → 清日志 → 复现 → **3 秒内**
读日志（主缓冲极小，hwcomposer 秒级刷掉）→ `/status` 交叉验证。

| 坑 | 事实 | 对策 |
|----|------|------|
| 单播 SSDP 时灵时不灵 | 内核把单播在聚屏与自带服务间二选一 | probe 组播回退；别写依赖单播的工具 |
| NIC up+IPv4 ≠ 组播就绪 | 开机初期内核/驱动的 `joinGroup` 可瞬时失败 | 加入失败后台带退避重试、不放弃；端口绑上即可用（`isPortBound`） |
| 跨网段组播全灭 | 路由器挡组播 | 控制端与电视同一 Wi-Fi |
| 出站端口策略 | 8123 拒 / 8080 放行 | 测试服务用常见端口 |
| /proc/net 被过滤 | udp/tcp 表缺 App 条目 | 用 dumpsys SurfaceFlinger 判视频层 |
| logcat 缓冲秒级刷掉 | hwcomposer 刷屏 | 清空→复现→立即读 |
| CmpbPlayer 黑盒 | 连 localhost 代理即断；prepare 可卡死无回调 | PROXY_ENABLED 默认关；prepare 卡死看门狗自救 |
| Seek 后位置冻结/抖动 | getCurrentPosition 停在 Seek 点 | 水位线+墙钟外推（已实现） |
| 时长解析错 | 个别 MP4 报短 | 无解，观察项 |
| screencap 截不到视频层 | 只截应用 UI | 配合 GetPositionInfo 判真实播放 |
| 蓝屏（视频层无内容） | MTK 硬件输出蓝色 | 空闲时藏 SurfaceView；退后台延迟 2.5s |
| release→prepare 异步竞态 | 厂商 reset 是异步的（reset_nosync），mReseted 跨实例共享：旧实例 teardown 砸中新实例 prepareAsync →「already reset」空操作 → 投视频先蓝屏 30s | releasePlayer 不再 reset()（守卫钉着）；prepare 卡死阈值 30s→10s 作安全网；准备窗口 MODE_VIDEO_PENDING 藏层+占位。见 `.agent/video-bluescreen-plan.md` |
| 手机浏览器吞 POST 响应 | Vivo/自带浏览器会把局域网 POST 的**响应**吞掉或改写成错误页（服务端日志铁证：已回 200、操作确实生效，前端却报「响应异常」失败） | 网页端成败一律以 `GET /files` 服务器复核为准（上传=列表增量、删除=名字消失、投送=非200引导看电视），不信 POST 状态码。守卫钉在 web-test |
| macOS zsh 无 setsid | 后台服务起不来 | 用受管后台任务（run_in_background） |
| Mac 全局代理 | 局域网 curl 被 58199 拦 | curl 加 --noproxy '*' |

### 实时调试会话（临时任务配方，非项目文件）

用户实测投屏时的标准抓日志姿势（本次会话反复使用）。两个任务都是
**内联命令、会话级托管**，磁盘上无脚本文件；本节就是它们的完整用法。

#### 任务 A：测试源 HTTP 服务（端口 8080）

**用途**：给合成投屏提供媒体源——验证播放/Seek/续播等功能时，
不依赖真实 CDN（CDN URL 带签名会过期，且不方便控制时长）。

```bash
# 启动（必须用受管后台任务，前台命令会被会话回收；macOS zsh 无 setsid）
cd /tmp/cast-test && python3 -m http.server 8080 --bind 0.0.0.0
```

| 事项 | 说明 |
|------|------|
| 服务内容 | `/tmp/cast-test/` 目录下所有文件（test.mp4=11s、bbb.mp4=10s） |
| 投屏地址 | `http://<Mac 的 IP>:8080/<文件名>` —— Mac IP 用 `ifconfig en0` 查，**换网络会变** |
| 加测试视频 | `curl -sL --noproxy '*' -o /tmp/cast-test/xxx.mp4 <下载地址>`（--noproxy 必加，见上表代理坑） |
| 何时需要 | Agent 做合成投屏测试时；**用户用手机真投 CDN 流时不需要** |
| 用完即停 | `pkill -f "http.server 8080"`（挂一整天会占着后台面板） |

#### 任务 B：实时日志捕获（/tmp/tv_session.log）

**用途**：全量录制电视 logcat——电视本地缓冲秒级被刷掉，文件捕获
是唯一保得住完整时间线的方式。用户实测时必须先开这个再让用户操作。

```bash
# 清空 + 开始录制（受管后台任务持续写文件）
adb -s 192.168.1.8:5555 shell "logcat -c"
adb -s 192.168.1.8:5555 logcat -v time > /tmp/tv_session.log 2>&1
```

| 事项 | 说明 |
|------|------|
| 分析 | `grep -E "控制指令\|播放状态\|收到投屏\|NOTIFY 失败\|播放错误\|重连" /tmp/tv_session.log \| tail -50` |
| 过滤刷屏 | 先滤掉 hwcomposer/wpa_supplicant/HiMarket/dalvikvm（占 90%+） |
| 文件增长 | ~10 万行/小时（刷屏为主），grep 分析不受影响，不必重启 |
| 电视重启后 | adb 断开 → 任务随之结束，需重连后重开捕获 |
| 时长/位置/URI 交叉验证 | 配合 `curl http://<电视IP>:49152/status`（见 §2） |

#### 标准排障时序

1. 起任务 B（清空 + 录制）→ 2. 用户操作投屏 → 3. 用户报现象（带时间点）
→ 4. 立即 grep 分析时间线 → 5. 需要画面证据时 `adb shell screencap`
（注意：screencap 截不到视频层，只能看到应用 UI）。

注意：这两个任务是**会话级**的（WorkBuddy 后台任务面板可见），
调试会话结束即可停掉；测试源文件在 `/tmp/cast-test/`，均不属于项目。
分析时要先过滤 hwcomposer/wpa_supplicant/HiMarket 刷屏行。

## 6. 修改纪律（铁律，违反会返工）

1. **八道闸门全绿才能提交**；断言总数变了必须同步期望值（probe 是 33/34 双态）。
2. **源码级守卫是资产**：修 bug 时同步加守卫（policy-test 里 report()），
   防回归；改代码结构时同步改守卫锚点。
3. **破坏性证伪**：修复前先复现红灯，修后确认绿灯；还原文件用反向
   replace，不用 `git checkout --`。
4. **错误态/已释放的 MediaPlayer 碰不得**：任何方法调用都触发 -38 回调
   （走事件不走异常，try/catch 挡不住）→ ERROR 刷屏死循环。必须用
   `nativePlayerDead`/`playerReleased` 标志挡调用本身（全套守卫已布）。
5. **乐观值必须有会终结的落地判据**：「未落地报目标位置」的持续性判据
   会成永真（进度条冻死在 Seek 点，已修）。
6. **协议面向真实控制点**：新字段/新 action 先查控制点会不会发
   （BubbleUPnP/腾讯视频/B站/网易云实测），SCPD 与 KNOWN_ACTIONS 同步。
7. **真机验证 = 电视屏幕实际观察**：screencap 截不到视频层，位置数据
   用 GetPositionInfo 交叉验证；需要人眼看画面时明确请用户确认。
8. **文档三处同步**：README（用户）、.agent/todo.md（状态）、本文件（Agent）。
9. **零第三方依赖**（无 gradle dependency、无 aar/jar）。**唯一明示例外**：
   `io/nayuki/qrcodegen/` 的 QR 编码核心（MIT，纯 Java 源码内嵌、无二进制）。
   引入理由：二维码编码**不能自己写**（Reed-Solomon + 掩码 + 版本选择，
   写错的表现是"扫不出来"，而人眼看不出来）；引入方式必须**源码级内嵌**，
   不引任何构建期依赖。例外只有这一处，再引第三方要先登记在这里。

## 7. 当前状态指针

- 版本 0.2.8（versionCode 23）—— 设备信息卡加基础信息四格（系统 / 处理器 / 内存 /
  存储，2×2 布局）为小更新。上一条时间线：0.2.7（22）二维码改右侧通高列 +
  安装包页不信 POST 响应；0.2.6（21）网页上传/删除/投送以服务器列表复核为准、
  文件夹入口移除；0.2.5（20）modeName 补 pending 分支 + **构建版本硬闸**；
  0.2.4（19）批 3.9 视频蓝屏修复 F1/F2/占位层；0.2.3（18）批 3.8 诊断日志；
  0.2.2（17）批 3.7、0.2.1（16）批 3.6、0.2.0（15）批 3.5 中等更新。
  历史版本已全部推送 GitHub（main）。
- **进行中：视频「先蓝屏约 30 秒」修复**（`.agent/video-bluescreen-plan.md`）。当前只落了
  **第 1 步 = 诊断日志**（批 3.8，零行为改动）：形态变化 / SurfaceView 显隐 + Surface
  生命周期 / `prepare 开始（带 surface=?）`·`结束（带耗时）`。**已定案分流**：真机
  `surface=true` + 厂商 `already reset` 栈 → 坐实 release/reset 竞态（§2.3）。
  **批 3.9 已落地治本 + 治标**（F1 去 reset / F2 阈值 10s / MODE_VIDEO_PENDING 占位层），
  0.2.4 真机验收通过（prepare 422ms、无 already reset）；F3（SWITCH_SETTLE_MS 接线）
  留作真机若复现再上。
- **发版规则（用户定的，每次打包都要遵守）**：**每次构建（拿去装机的 dist 包）都必须
  更新版本号** —— 现在有机械强制：`build.sh dist` 开头硬闸比对 `dist/.last-build-version`，
  同版本号连出两包直接红。升版用 `./tools/build.sh version [patch|minor|major]`
  （自动改 build.gradle 的 versionName + versionCode），别再手改。
  规则本身不变：改 `app/build.gradle` 的 `versionName`，界面「版本」那一行读的就是它
  （不写死字面量）。**小更新**（修 bug、小改进）→ 末位 +1（0.1.12 → 0.1.13）；
  **中等更新**（新能力、行为变更）→ 中间位 +1、末位归零（0.2.1 → 0.3.0）；大更新 → 首位。
  `versionCode` 每次递增 1（只给系统比新旧用，**界面上不显示**）。
  教训：0.2.4 曾连着出两个 dist 包（批 3.9 + modeName 修复），装机分不清跑的哪个 ——
  这正是这条纪律存在的原因。README/AGENTS 引用版本号一律写 `<版本>` 占位，
  避免每次升版都要 grep 修漂移。
- **结束投屏只有一个入口：遥控器返回键**（`MainActivity.onKeyDown` → `service.onStop()`）。
  用户明确否掉了「播完 N 秒无控制指令就自动收尾」那种替用户猜意图的做法 ——
  别再往服务里加「自动回空闲」的判据。Auto-Stop（`checkAutoStop`）是另一回事：
  它管的是「还在播、控制点却真的走了」，判据是订阅者数，且从不过问 STOPPED 态。
- 未完成/观察项清单：`.agent/todo.md` §五（真机发现）、§六（生态调研 P1/P2）。
- 代理（MediaProxy）默认关：CmpbPlayer 黑盒两症状未定根因，开
  `PlaybackPolicy.PROXY_ENABLED=true` 可继续迭代。
