# 轻音 2.0 原生重构交付说明

## 概述

轻音 2.0 已从 goapk/WebView 运行时迁移为独立的 **Kotlin 原生 Android 工程**。应用不再创建 WebView，也不再使用 HTMLAudioElement、JavaScript 播放队列或 WebView 媒体桥接。界面使用 Jetpack Compose；播放由 `MediaSessionService` 内唯一的 Media3 ExoPlayer 实例负责，系统通知、锁屏、蓝牙、耳机按键、后台播放和应用内控制器共享同一播放状态。[1] [2]

应用显示名已统一为 **轻音**，包名仍保留为 `im.molan.music`，用于支持从 1.3.x 覆盖升级。新下载固定写入 `Music/轻音`。旧目录 `Music/Molan Light Music` 中的文件无需迁移，仍会由系统媒体库扫描发现。

## 已迁移能力

| 能力 | 原生 2.0 实现 | 状态 |
|---|---|---|
| 音频播放、队列和系统媒体控制 | `PlaybackService` + Media3 ExoPlayer + MediaSession。队列、上一首、下一首、拖动、列表循环、单曲循环与随机模式由原生播放器统一管理。 | 已完成 |
| 快速切歌稳定性 | 无 `<audio>`、`audio.load()` 或 `play()` Promise；播放命令收敛至 Media3 服务，消除 WebView 切歌竞争。 | 已完成 |
| 本地音乐扫描 | Android MediaStore 直接读取 `content://` URI，不导入、不复制音乐文件。 | 已完成 |
| 自定义目录 | Storage Access Framework 目录授权与递归扫描；URI 授权路径由 DataStore 保存。 | 已完成 |
| 下载 | Android DownloadManager 固定写入 `Music/轻音`，下载完成后系统通知可见。 | 已完成 |
| 歌词 | Kotlin LRC 解析器支持多时间戳、翻译歌词、二分定位与全屏歌词视图。 | 已完成 |
| 网易云搜索、URL、歌词 | Kotlin OkHttp 直接请求用户配置的 NCMC 服务；搜索结果由 Media3 播放。 | 已完成 |
| 网易云二维码登录 | Kotlin 原生生成二维码、1.8 秒轮询、保存 Cookie 与账户昵称；不依赖网页登录页。 | 已完成，需真机扫码验证 |
| 主题与音质 | Compose 深色模式、DataStore 持久化、播放与下载共享音质配置。 | 已完成 |
| QQ 音乐登录和歌单 | 旧方案依赖不稳定的非公开 QQ 网页授权且已出现授权码缺失；原生版明确不复用该会话链路。 | 兼容降级 |

## 已验证结果

| 校验项 | 结果 |
|---|---|
| Kotlin Debug 编译 | 通过 |
| LRC 单元测试 | 2 项通过：多时间戳/翻译合并与二分当前行定位。 |
| APK 包名 | `im.molan.music` |
| 显示名 | `轻音` |
| 版本 | `2.0.0-native`，`versionCode 20` |
| Android API | `minSdk 24`，`targetSdk 35`，`compileSdk 35` |
| APK 签名 | APK Signature Scheme v2 验证通过。 |
| 覆盖升级兼容 | 使用与 1.3.15 相同的 SHA-256 签名证书：`9f5d52b75b0410a2c96c1d1ded5e0da5de87dbc93cee6db3f94ac9f08c8ddd88`。 |
| APK SHA-256 | `86ebf53039bfdd08424469fdbcc671c9889d2af2f55739ae3972ea6400e19673` |
| 默认 NCMC 服务探测 | `/cloudsearch`、`/lyric`、`/login/qr/key` 与 `/login/qr/create` 均返回适配所需结构。 |

## 真机验收建议

首先覆盖安装 APK 并确认“轻音”可以直接替代旧版本。首次进入“本地”页面，应允许媒体权限并验证 MediaStore 扫描；随后可使用“文件夹”选择一个包含音频的目录，并在重启后验证授权目录可以恢复。

播放测试应覆盖连续快速点歌、进度拖动、上一首/下一首、列表循环、单曲循环、随机模式、锁屏和通知栏控制。在线功能应测试搜索、播放地址解析、歌词显示和下载后的 `Music/轻音` 文件可见性。网易云二维码流程需在真机用网易云音乐 App 扫码确认，随后核对“我的”页昵称以及登录后的网络请求。

> QQ 音乐原生登录没有在 2.0 中伪造为“已完成”。要重新开放此能力，需要正式的腾讯音乐授权，或在法律与兼容性边界明确后单独维护上游网页协议适配；不能以现有不返回授权码的私有接口作为原生核心依赖。

## 构建命令

```bash
cd /home/ubuntu/qingyin-native
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk
export ANDROID_SDK_ROOT=/home/ubuntu/android-sdk
./gradlew --no-daemon \
  -Pqingyin.legacy.keystore=/安全路径/legacy-debug.keystore \
  :app:testDebugUnitTest :app:assembleDebug
```

输出文件为 `app/build/outputs/apk/debug/app-debug.apk`。传入 `qingyin.legacy.keystore` 属性时，工程会复用现有 goapk PKCS12 签名密钥，以确保升级签名一致；该密钥及其本机路径不可提交到 GitHub。未传入该属性时，Gradle 使用默认调试签名，仅适用于新安装测试。

## 参考

[1] [Android Developers：MediaSessionService 后台播放](https://developer.android.com/media/media3/session/background-playback)

[2] [Android Developers：Media3 ExoPlayer 入门](https://developer.android.com/media/media3/exoplayer/hello-world)

[3] [Android Developers：Jetpack Compose 设置](https://developer.android.com/develop/ui/compose/setup)
