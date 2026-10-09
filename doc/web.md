# 扫码上传与安装包页（局域网 Web 功能）

> 本文是 README 拆出的深度文档，总目录见 [README](../README.md)。

同一个 HTTP 服务除 DLNA 外还挂了一组网页端点：手机浏览器上传本地文件直接投、
U 盘里的 APK 让电视列出来一键进系统安装器。

**扫码网页传文件（批 1–2）**：同一个 HTTP 服务还挂了一组
网页端点 —— 浏览器打开 `http://<电视IP>:端口/`（带 `Accept: text/html`）就是上传页
（不带该头时 `/` 仍回 `device.xml`，兼容老控制点）。**电视空闲时会把这个地址画成
二维码**，手机扫一下就进去，不用手打 IP。页面上能**多选文件一起传**、
显示上传进度与**剩余空间**、把已上传的文件**直接投到电视**或**删掉**。
（曾有「选文件夹」入口，2026-10-02 按用户要求移除：目录选择在部分安卓浏览器上是
特性检测误判，留着就是「点了没反应」的坑，多选已覆盖需求。）
上传/删除/投送的成败都以服务器真实列表复核为准 —— 手机浏览器会吞掉或改写局域网
POST 的响应（真机踩坑：文件明明传上去了，页面却报「响应异常」失败）：上传比对
`GET /files` 列表增量、删除确认名字已消失、投送非 200 时引导看电视。相关路由：

| 路由 | 作用 |
| --- | --- |
| `GET /` | 上传页（仅当 `Accept` 含 `text/html`；否则 device.xml） |
| `POST /upload` | multipart 流式落盘 → `getFilesDir()/uploads`；空间预检 `411 → 413 → 507` |
| `POST /cast` | 播放已上传的文件（复用既有渲染器） |
| `POST /delete` | 删掉一个已上传的文件（盒子空间紧，必要） |
| `GET /files` | 已上传文件列表 + 剩余空间（JSON） |
| `GET /media/<名字>` | 提供媒体流（支持 `Range`/`HEAD`）—— 播放**实际走这里**，不用 `file://` |
| `GET /status` | 诊断 JSON（含新增的 `storage` 段：上传目录 / 可用空间 / U 盘挂载点） |

> **为什么不 `file://`**：真正开文件的是另一个进程 `mediaserver`（uid `media`），
> 它穿不进应用私有目录（`drwx------`）—— 真机实测直接 `error (1, -2147483648)`。
> 改由盒子自己的 HTTP 服务供流后，顺带修好了老芯片「视频被判成纯音频」的老毛病
> （`getVideoWidth()` 恒 0，只能靠 `Content-Type` 探测）。

**外接存储装应用（批 3.5）**：上传页页顶多了一个
「安装包（U 盘里的 APK）→」链接，进去就是安装包页（`GET /apk`）。它**只扫外接可移动
设备**（U 盘 / SD 卡，即 `LocalStore.removableMounts()` 认出的那些卷），**不列盒子内部
存储** —— 内部存储里的 APK 系统安装器（另一个进程）根本读不到，列出来只会给用户一个
「点了没反应」的按钮。相关路由：

| 路由 | 作用 |
| --- | --- |
| `GET /apk` | 安装包页（HTML，ES5、零外链） |
| `GET /apk/list` | 扫描结果（JSON：`apps` / `scanning` / `truncated` / `installAllowed` / `roots`） |
| `POST /apk/refresh` | 强制重扫（页面「刷新」按钮）—— 单开一条路，因为查询串在 HTTP 层就被剥掉了 |
| `POST /apk/install` | 触发系统安装器（本功能**唯一有副作用**的路由） |

安装就是发一个 `ACTION_VIEW` + `application/vnd.android.package-archive` 的 Intent，
把电视上的**系统安装器**叫起来 —— 真正的安装要**用户拿遥控器在电视前确认**。
`targetSdk 19` 让这条路**免掉** FileProvider、`REQUEST_INSTALL_PACKAGES`、运行时权限三件套
（`file://` 在 `targetSdk < 24` 上不抛 `FileUriExposedException`）。

两条加固把「任意安装」这个新攻击面收窄：① `/apk/install` **只接受本次扫描结果里出现过
的路径**（服务端白名单）；② 落点再叠一道 `LocalStore.isUnder`（canonical 前缀校验，防
`..` 穿越、防私有目录）。即便这样，局域网内最多也只能让电视**弹一个安装确认框** ——
装不装成，得人（遥控器）点头。

「未知来源」开关（`Settings.Secure.INSTALL_NON_MARKET_APPS`，API 15 上是全局开关）关着时，
页面顶部只显示一条**文字指引**（`设置 → 安全 → 未知来源`），**不提供跳转按钮**；
`/status` 里也加了 `installAllowed` 布尔，排障一眼可见。

扫描要面对**可能很大的 U 盘**，硬要求是**不卡 UI、不 OOM、可中断**：扫描跑在专用线程
（不是主线程，也不是 HTTP 连接线程）、**迭代而非递归**（深目录不爆栈）、只 `stat` 不读文件
内容（元数据用 `getPackageArchiveInfo`，只读 zip 中央目录，几十 MB 的包也不整包读入）、
**深度 ≤ 8 / 条数 ≤ 300 / 时间 ≤ 8s** 到顶即停、新扫描取消旧扫描（连点刷新不堆叠线程）、
结果按代次缓存。策略全在纯逻辑内核 `ApkScan` 里（零 Android 依赖），所以能在桌面用临时目录
把每条边界都断言一遍。

> ⚠️ **装的是聚屏自己时会断服务**：安装过程中系统会杀掉本进程 → 前台服务随之中断 →
> 装完需**重新打开聚屏**。这是系统行为，无解。装别的 APK 不受影响。

> ⚠️ **两条只能在真机上验**：① U 盘（vfat）上的 APK 能否被**系统安装器**读到
> （不可从应用私有目录的失败外推）；② 0.6GB 盒子插大 U 盘时扫描**不卡、不 OOM**。

**电视上的二维码（批 2）**：空闲形态下主面板会显示一个二维码，内容就是上传页地址
（与 `device.xml` 的 `presentationURL` **同源**：同一个本机 IP、同一个实际监听端口 ——
端口回退时两处一起变）。布局上它在**右侧通高独立列**里（上下各贴标题与操作说明），
不再嵌在引导卡片中——卡片里塞一张 196dp 的码会把整块撑高、顶满屏幕
（2026-10-02 按用户要求重构）。位图由 `QrRenderer` 逐格画：上游 Nayuki 库的 `toImage()`
依赖 `java.awt` / `javax.imageio`，Android 没有，所以只取它算好的模块矩阵，
攒成一个 `int[]` 一次 `setPixels` 灌进去（0.6GB 的机器上，逐点 `setPixel` 会肉眼可见地卡）。
地址没变就不重画（`refresh()` 每 tick 路过这里），换图时 `recycle()` 旧位图。

> **依赖说明**：二维码编码（Reed-Solomon + 掩码 + 版本选择）**不适合自己写** ——
> 写错的表现是「扫不出来」，而人眼看不出来。这里把
> [Nayuki QR Code generator](https://www.nayuki.io/page/qr-code-generator-library)
> 1.8.0 的 Java 源码（MIT）**逐个文件内嵌**进 `app/src/main/java/io/nayuki/qrcodegen/`，
> 不引任何构建期依赖（无 gradle dependency、无 aar/jar），是项目「零第三方依赖」
> 铁律的**唯一明示例外**。为 API 15 只改了两处：`Objects.requireNonNull` → 显式判空、
> 取 UTF-8 字节走 `Charset`（`java.util.Objects` 与 `StandardCharsets` 都要 API 19）。
