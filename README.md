# 轻音

轻音是一个无广告、本地音乐优先的 Android 音乐客户端。当前主线是独立的 **Kotlin 原生 Android 实现**：Jetpack Compose 负责界面，Media3 ExoPlayer 与 `MediaSessionService` 负责唯一播放队列、后台播放、锁屏、通知栏和蓝牙媒体控制。应用不再以 WebView 或 HTMLAudioElement 作为播放运行时。[1] [2]

当前原生快照对应 **2.0.0-native（versionCode 20）**，显示名称为“轻音”，包名保持 `im.molan.music`，以支持从旧版本覆盖升级。

> 原生版优先保证播放性能和本地音乐体验。网易云搜索、歌词、播放 URL、DownloadManager 下载与二维码登录已迁移到 Kotlin；QQ 音乐登录依赖不稳定的上游非公开网页登录流程，当前不会在原生主线伪造为可用功能。

## 目录

| 目录 | 说明 |
|---|---|
| `native-android/` | 当前 Kotlin 原生主线。包含 Compose 界面、Media3 播放服务、本地扫描、SAF、系统下载、歌词、NCMC 搜索与网易云二维码登录。 |
| `legacy-webview/app/` | 历史 WebView 前端快照，仅用于回溯旧实现和迁移对照，不是 2.0 的运行时。 |
| `legacy-webview/goapk/` | 历史 goapk 定制运行时与 Java 原生桥接。 |
| `legacy-webview/tests/` | 历史 WebView 版本的回归测试。 |
| `docs/` | 原生迁移与历史设计说明。 |

## 原生构建

原生工程需要 Android SDK 35、JDK 17 和 Gradle Wrapper。普通 Debug 构建可以直接执行：

```bash
cd native-android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

若需要覆盖升级旧版 `im.molan.music`，必须使用与旧 APK 相同的签名密钥；密钥仅通过本地属性传入，绝不提交到仓库：

```bash
./gradlew --no-daemon \
  -Pqingyin.legacy.keystore=/安全路径/legacy-debug.keystore \
  :app:testDebugUnitTest :app:assembleDebug
```

APK 输出在 `native-android/app/build/outputs/apk/debug/app-debug.apk`。原生迁移范围、验证结果、QQ 兼容降级和真机验收清单见 [`docs/QINGYIN_2.0_NATIVE_MIGRATION.md`](docs/QINGYIN_2.0_NATIVE_MIGRATION.md)。

## 许可证边界

项目未复制 rRemix/APlayer 或其他 GPL 音乐播放器的源代码。其架构仅作为调研参考；当前 Kotlin 原生实现独立使用 AndroidX Media3、Compose 与系统 API。[1] [2]

## 参考

[1] [Android Developers：MediaSessionService 后台播放](https://developer.android.com/media/media3/session/background-playback)

[2] [Android Developers：Media3 ExoPlayer 入门](https://developer.android.com/media/media3/exoplayer/hello-world)
