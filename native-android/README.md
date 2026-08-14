# 轻音 Android 原生模块

本目录是轻音的 Kotlin/Jetpack Compose 原生 Android 应用。应用使用 Media3 提供播放服务，Room 与 DataStore 管理本地数据，并通过自维护下载队列支持续传、元数据写入和 MediaStore/SAF 发布。

## 环境要求

| 项目 | 版本或要求 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | API 35 |
| Android Build Tools | 35.0.0 |
| Gradle | 使用仓库内 Wrapper（8.10.2） |

首次在本地构建时，在 `native-android/local.properties` 写入 Android SDK 位置；该文件不得提交。

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

## 常用命令

以下命令均应在 `native-android` 目录执行。

```bash
# 运行 JVM 单元测试
./gradlew testDebugUnitTest

# 运行 Android lint
./gradlew lintDebug

# 构建可安装的 debug APK（使用 Android 默认 debug 签名）
./gradlew assembleDebug

# 执行本地质量门禁
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Release 构建默认不启用 R8，以便逐步验证规则。发布候选必须额外执行下列命令，确认压缩与资源裁剪不影响播放、下载、歌词、通知和元数据写入。

```bash
./gradlew assembleRelease -PenableR8=true
```

## 签名规则

`debug` 构建必须使用 Android 默认调试签名；发布签名仅允许注入到 `release`。私钥、签名容器、口令和本地签名属性均不能提交到 Git，也不能写入构建日志。自动化发布应从受保护的密钥存储或持续集成密钥变量中读取发布签名。

## 质量门禁

仓库中的 Android 质量工作流会在 `main` 分支推送和 Pull Request 时执行以下步骤：

1. 验证 Gradle Wrapper；
2. 使用 JDK 17 和 Android API 35 建立可重复的环境；
3. 运行 `testDebugUnitTest`、`lintDebug` 和 `assembleDebug`；
4. 保存测试与 lint 报告作为短期构建附件。

新增核心逻辑时，应同步增加单元测试；涉及 Android 框架、存储迁移、SAF/MediaStore、前台服务或 Media3 集成时，应增加仪器测试。性能相关改动应记录冷启动、曲库扫描、首帧播放和歌词滚动的前后基线。

## 架构约定

`MainViewModel` 负责组合页面状态与用例编排，不应继续接纳新的存储缓存或线程同步细节。可复用、无 UI 依赖的业务逻辑应下沉到独立组件并配套 JVM 测试；例如 `LocalTrackMatchIndex` 负责候选索引、正负缓存与原子替换，`DownloadStatePersistencePolicy` 负责下载进度的持久化节流。

下载进度可以即时更新 UI，但不应在每个进度事件都重写完整任务快照。状态转换、失败和完成必须立即落盘；纯字节进度按照策略节流写入，进程异常时以 `.part` 文件长度恢复。
