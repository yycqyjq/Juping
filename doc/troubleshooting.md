# logcat 日志判读

> 本文是 README 拆出的深度文档，总目录见 [README](../README.md)。

先试最快的路：浏览器打开 `http://<电视IP>:49152/status`（见 README「排障」）。
需要连电脑时，这一篇逐条讲每类日志说明什么、哪些是**正常路径**别当故障。

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
- **`收到与当前相同的地址，幂等忽略（不重建播放器）`** —— 控制点把**同一个**
  URL 又下发了一遍（拖进度条时的常见行为）。这条同样是**正常**的：
  旧代码在这里会释放并重建播放器，电视上先闪一下蓝屏、再重新缓冲 ——
  用户看到的就是「拖一下就断开重连」。
- **`已广播 ssdp:alive（6 个 NT × 3 轮）`** —— 启动时的主动广播。
  **没有这一行**，就意味着网易云音乐那类「只监听广播、不主动搜索」的控制点
  根本看不到这台设备 —— 而腾讯视频会主动搜索，所以照样搜得到。
- **`已广播 ssdp:byebye`** —— 服务关闭时通知控制点「我走了」。
  不发的话，设备会在 App 的列表里挂到缓存过期（最长 30 分钟）。
- **`自动续播下一曲: …`** —— 播放列表续播生效：当前曲目自然播完，
  自动切到 `SetNextAVTransportURI` 预告过的下一曲。控制点（手机 App）
  通过轮询 GetMediaInfo / GetPositionInfo 跟上即可。
- **`prepare 卡死 …ms，第 N 次重建播放器自救`** —— prepareAsync 发出后
  超过 10 秒无任何回调（媒体服务卡死），自动释放并重建播放器自救。
  连续 2 次重建仍卡死会打 `prepare 连续 2 次重建仍卡死 —— 疑似电视
  媒体服务异常，建议重启电视后重试`：那是**诚实边界**，重启电视即可恢复。
- **`形态: IDLE → VIDEO`** · **`SurfaceView 显隐: VISIBLE（形态 VIDEO）`** ·
  **`Surface created` / `Surface changed: WxH` / `Surface destroyed`** ·
  **`prepare 开始: surface=true|false`** · **`prepare 结束: 耗时 X ms（成功）`** ——
  批 3.8 新增的**诊断日志**（「视频投屏先蓝屏约 30 秒」取证用，**零行为改动**）。
  形态变化只在形态真的变时打一条、SurfaceView 显隐只在可见性真的变时打一条
  （都不是每拍）。**`prepare 开始: surface=false` 是关键判据**：说明 prepare 发出
  那一刻输出面还不存在 —— 坐实「prepare 早于 Surface」（厂商栈对没有输出面的视频
  prepare 不返回，直到 10s 看门狗重建才成功）。日志里**不打 URL**（带签名，属敏感）。
- **`控制点已离开（无订阅者，最后指令 Ns 前），自动停止投屏`** ——
  Auto-Stop 生效：最后一个订阅者过期且 120 秒内无任何控制指令，
  电视自动停止。这是特性不是故障；纯投放型控制点（从不订阅）不会触发。
- **`代理路径 prepare 超时，降级直连重试`** —— 本地预取代理在当前
  厂商播放器栈上不兼容（默认已关，见 [README「已知边界」](../README.md#已知边界)），看到这行说明
  开关是开着的且已自动降级直连。
- **`Content-Type 为 video/mp4…从音乐形态切回视频形态`** —— 视频误判
  音频的自愈路径（getVideoWidth 恒 0 的平台上靠 Content-Type 翻案）。

## 排查「手机上的进度条不跟着走」

这类问题**只能**靠日志定位，而它默认是**关着**的 —— 位置轮询是每秒一次的高频动作，
无条件打日志会在 0.6GB 的盒子上刷屏、并拖慢响应。需要时动态打开：

```bash
adb shell setprop log.tag.UpnpHttpServer DEBUG
adb logcat | grep -E "GetPositionInfo|控制指令"
```

- **`GetPositionInfo → RelTime=… TrackDuration=… hasMedia=…`** ——
  控制点每次轮询位置都会打一条。据此可以判断两件事：
  - **一条都没有** → 控制点压根没在轮询。那它多半是等事件里的
    `RelativeTimePosition`，问题不在我们这一侧；
  - **有，但 `RelTime` 一直不变** → 要么位置真的没在走（回去查卡死 / 重连），
    要么 `TrackDuration` 是 `00:00:00`（拿不到总时长，控制点算不出比例，
    进度条就画不出来）。
- **`控制指令: service=AVTransport action=…`** —— 控制点发来的每一条 SOAP。
  拖进度条时如果看到的是 `SetAVTransportURI` 而不是 `Seek`，
  就正好印证了上面那条幂等修复要挡的就是这个行为。

> 关掉：`adb shell setprop log.tag.UpnpHttpServer ""`（或重启服务）。
