# 签名

> 本文是 README 拆出的深度文档，总目录见 [README](../README.md)。

release 密钥在 `keystore/juping-release.jks`，密码在 `keystore.properties`。
**这两个文件都被 `.gitignore` 忽略**，不会跟着代码跑出去。

> **务必备份这两个文件。**
> 密钥丢了，就没法对已经装在盒子上的聚屏做覆盖升级 —— 只能先卸载再装，
> 而卸载会清掉已保存的设备 UUID（手机投屏列表里会多出一台"新"设备）。

## 为什么密钥用 SHA1withRSA

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

