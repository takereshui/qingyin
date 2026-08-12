# Molan Light Music

Molan Light Music 是一个无广告、以本地音乐优先播放为核心的 Android 音乐客户端。它使用 WebView 承载轻量前端，并通过定制的 `goapk` 原生运行时实现系统下载、本地媒体扫描、Android 原生本地播放器、媒体会话和沉浸式界面。

当前源码快照对应 **1.3.13（versionCode 17）**。该版本包含网易云 NCMC 登录、APK 内独立 QQ 音乐 QR 登录、网易云/QQ 歌单合并、ChKSz QQ MID 音源解析、歌词缓存和本地优先播放。

> QQ 登录与歌单读取依赖 QQ 音乐非公开网络交互，可能受上游策略影响。本项目仅供用户登录自己的账户、读取个人歌单及播放有合法收听权限的内容；不得用于绕过会员、付费或版权限制。

## 目录

| 目录 | 说明 |
|---|---|
| `app/` | 纯 HTML/CSS/JavaScript 音乐应用与本地资源。 |
| `goapk/` | 定制 APK 构建器及 Android 原生运行时；`java/QQMusicSession.java` 提供 APK 内 QQ 会话能力。 |
| `tests/` | 本地匹配、扫描缓存、原生本地播放器和 QQ 集成回归测试。 |
| `docs/` | 独立 QQ 登录、音源定位与接口调研说明。 |

## 构建

构建依赖 Go、JDK 8 兼容编译器、Android `android.jar`、D8 和 `apksigner`。本仓库不提交生成的 `classes.dex`、`goapk` 二进制或 APK。

```bash
cd goapk
rm -rf /tmp/goapk-javac /tmp/goapk-dex
mkdir -p /tmp/goapk-javac /tmp/goapk-dex

javac --release 8 -classpath "$HOME/.goapk/android.jar" \
  -d /tmp/goapk-javac java/*.java
java -cp "$HOME/.goapk/r8.jar" com.android.tools.r8.D8 \
  --release --min-api 24 --lib "$HOME/.goapk/android.jar" \
  --output /tmp/goapk-dex/ /tmp/goapk-javac/com/zapstore/goapk/runtime/*.class
cp /tmp/goapk-dex/classes.dex internal/embed/classes.dex

make build
./goapk build -s ../app \
  --package im.molan.music --name "Molan Light Music" \
  --version-code 17 --version-name "1.3.13" \
  ../molan-light-music.apk
apksigner verify --verbose --min-sdk-version 24 ../molan-light-music.apk
```

## 测试

```bash
node tests/test_local_match.cjs
node tests/test_local_scan_cache.cjs
node tests/test_native_local_player.cjs
node tests/test_qq_integration.cjs
```

## QQ 登录与接口边界

QQ 二维码、会话轮询、加密 Cookie 保存与“我的歌单”读取均由 Android 原生层完成。原始 QQ Cookie 不会返回给网页 JavaScript；只会加密保存到 Android Keystore 保护的应用私有存储。QQ 歌曲播放、下载和 LRC 使用用户在应用设置中填写的 ChKSz API Key 按 MID 解析。

本项目没有复制 `l-1124/QQMusicApi` 的 GPLv3 源码，仅对其公开能力模型进行调研。具体设计与许可证说明见 [`docs/MOLAN_V1.3.13_EMBEDDED_QQ_LOGIN.md`](docs/MOLAN_V1.3.13_EMBEDDED_QQ_LOGIN.md)。
