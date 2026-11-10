# 废土 Demo V12 备份

2026-11-10 保存的最新工程。Spatial SDK `6.2.6`；Android versionName / versionCode：`3.0 / 3`。

最新版安装包位于 GitHub Release `v12`，文件 `spatial-wasteland-v12.apk`。安装包的 SHA-256 与原交付文件一致。Release 同时提供 SHA256SUMS、源码清单和交付说明。

源码、JVM/设备测试、模型原件、运行时资源、设计与资产来源说明均随仓库保存。SOURCE-MANIFEST.json 记录原文件的路径、字节数、SHA-256、Git blob 和可执行模式。生成目录、历史 APK、机器配置及含临时授权的 gateway 流程归档未纳入版本库。

## 恢复构建

安装 JDK 21 与 Android SDK 35，设置 JAVA_HOME、ANDROID_HOME / ANDROID_SDK_ROOT；或在本机创建 local.properties 写入 sdk.dir。然后执行：

```sh
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

依赖仓库地址保留原工程配置；首次构建需要能访问这些 Maven 仓库。指尖或视觉验证范围以原交付说明为准。

## 本次恢复验证

2026-11-10 从排除缓存与本机配置的独立副本执行 `./gradlew --offline --no-daemon --max-workers=2 :app:assembleDebug`，构建成功。日志见 RESTORE-BUILD.log。未操作设备或重跑指尖验证。
