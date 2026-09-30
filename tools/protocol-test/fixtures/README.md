# 测试夹具（fixtures）—— 不是发布产物

`selftest-min-sdk-14.apk` **不是应用、不会被安装、也不该被任何人拿去发布**。
它只是一个 327 字节的**测试夹具**，唯一用途：

> 给 `tools/protocol-test/verify-device-selftest.sh` 一个能被
> `tools/apk_info.py` 真正解析出 `minSdkVersion` 的最小 APK。

## 为什么需要它

`verify-device-selftest.sh` 会驱动 `tools/verify-on-device.sh` 跑一遍真机验收
管道。那条管道里有一道「选包 + minSdk 比对」闸门：它原本只认 `dist/juping-*.apk`。
而 `dist/` 是构建产物、被 `.gitignore` 忽略 —— **CI 的干净检出里根本没有它**，
于是 `verify-on-device.sh` 在选包处直接 `exit 2`，把自测 6 条断言全部带红
（协议闸门在 Ubuntu 上「macOS 能过、CI 全红」的真正根因）。

把夹具随仓库提交，自测就不必再依赖 `dist/`。

## 内容

仅一个二进制 `AndroidManifest.xml`（AXML），字段：

| 字段 | 值 |
|------|-----|
| `package` | `com.juping.selftest.fixture` |
| `minSdkVersion` | `14`（与 app 的 minSdk 一致 → 正面自测用） |
| `targetSdkVersion` | `15`（与目标设备 API 一致） |

**故意不带** dex / 资源 / 签名 —— 它只被 `apk_info.py` 读 `minSdk`，不参与安装。

## 为什么必须「能被解析出 minSdk」

自测里有一条**反面对照**：假设备 API 设成 `13`（< minSdk 14），闸门必须拦住并
打印 `这个包装不上`。如果夹具是个读不出 minSdk 的空壳（比如随便一个 dummy 文件），
`MIN_SDK` 为空 → 闸门被跳过 → 反面对照会**假绿**。所以夹具必须是**真能被解析**的。

## 重新生成

```bash
python3 tools/protocol-test/fixtures/make_fixture.py
```

生成器把 zip 时间戳固定，产物**逐字节可复现**（改动了请一并提交）：

```
sha256  8fa2e08143284b3c0a0ef59113ea8f0c5630f7e20e03efdb8b460d9c5dfaa37e
```
