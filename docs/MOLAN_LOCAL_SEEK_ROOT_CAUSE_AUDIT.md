# Molan Light Music 本地音源拖动失败排查报告

> 本报告为**只读排查结论**。本轮未修改任何应用源代码，未重新编译 APK。

## 结论摘要

本地歌曲拖动失败的核心并不在进度条 UI，而在于当前应用把 Android `content://` 本地媒体包装成 WebView 虚拟 HTTPS 响应后，假定每个来源都能够提供**可靠、可随机定位、长度可知的文件描述符**。这个假定对部分 MediaStore 条目、部分厂商实现以及 SAF 授权目录并不成立。

当前代码在拖动后会让 WebView 发出媒体 Range 请求。代理层尝试将该请求映射到 `ParcelFileDescriptor` 的 `FileChannel.position(start)`；一旦文件描述符是不可随机访问的流、无法报告长度，或者内容提供方拒绝定位，底层异常会被捕获并转换为 HTTP `416 Range Not Satisfiable`。WebView 的音频渲染器随后将媒体源置为错误状态，因此表现为“本地音源失效”。网页侧恢复逻辑只能在错误发生后重新装载 URL，不能消除该底层不兼容。

| 判定 | 置信度 | 依据 |
|---|---:|---|
| **主因：`content://` 文件描述符并非始终支持可靠随机访问** | 高 | Range 代理直接调用 `stream.getChannel().position(start)`；定位异常会进入统一异常分支并返回 416 |
| **放大因素：未知文件长度时仍宣告 `Accept-Ranges: bytes`** | 高 | 代理在总长度不明时返回 200 完整流，但仍写入 `Accept-Ranges`；WebView 可能继续用 Range 方式寻址 |
| **放大因素：定位异常被吞掉，真机上无法区分原因** | 高 | Range 解析、通道定位和读取都在同一 `catch (Exception)` 中，未记录 URI、Range、长度或异常类型 |
| **授权目录缓存 URL 可能过期** | 高（仅自定义目录） | 自定义目录 URL 使用内存 `UUID` token；扫描时会 `customMusicDocuments.clear()`，但 IndexedDB 本地歌曲缓存会长期保存旧 token URL |
| **网页层恢复不能保证成功** | 中 | 恢复逻辑只是重新赋回同一虚拟 URL；若根因是该 URI 的随机读取不兼容，第二次加载仍会失败 |

## 已核对的链路

| 环节 | 当前行为 | 风险 |
|---|---|---|
| MediaStore 扫描 | 将媒体 ID 映射为 `https://appassets.androidplatform.net/__goapk_media/{id}.audio` | 不是原生媒体播放器直接读取 `content://`，而是经过 WebView 拦截代理 |
| 自定义目录扫描 | 用随机 token 映射为 `__goapk_document/{token}.audio` | token 仅保存在 Activity 内存；应用重启或再次扫描后，持久缓存中的旧 URL 无法再解析 |
| WebView 媒体请求 | `shouldInterceptRequest` 返回 `WebResourceResponse` | 该机制不是完整 HTTP 服务，必须严格模拟 Range、长度、可定位性和错误语义 |
| 拖动定位 | 网页 `<audio>` 设置 `currentTime`，WebView 请求 Range | 需要来源具备真正随机访问能力 |
| 原生 Range 代理 | 使用 `ParcelFileDescriptor.AutoCloseInputStream` 与 `FileChannel.position(start)` | 对管道式或虚拟内容提供方，`position()` 可以失败；现有代码将失败转换为 416 |
| 网页错误恢复 | 本地音源报错后重载原 URL 并尝试恢复旧时间 | 无法修复同一 URI 的随机访问能力不足 |

## 已完成的只读验证

我执行了以下检查，没有对项目代码进行写入：

| 检查 | 结果 | 边界 |
|---|---|---|
| 原生运行时代码编译检查 | 通过 | 说明 Java 代码语法和 Android API 调用可编译，不说明厂商 ContentProvider 的运行时能力 |
| 独立随机 Range 字节边界实验 | 通过 | 用普通可随机访问文件验证 `FileChannel.position + 有界流` 的数学语义正确 |
| WebView/MediaStore 真机日志 | 无法取得 | 当前沙箱未连接用户 Android 设备，也没有 ADB 日志流；因此无法直接读取失败时的 Range Header、PFD 类型和异常栈 |

> 关键结论：测试证明“普通文件的随机 Range 逻辑”成立，但**不能证明手机上的 `content://` 提供方可将同一逻辑用于随机定位**。用户持续复现失败，正是这一差异的直接证据。

## 为什么 1.3.11 仍可能失败

1.3.11 修正了 Range 响应边界，适用于普通、可定位的文件描述符；但它仍以 `FileChannel.position(start)` 为前提。MediaStore 或厂商存储提供方若返回的是管道式文件描述符、云端占位文件、受限媒体或长度未知的 URI，该前提依然不成立。

此外，当前异常分支把“内容提供方不支持 seek”“文件描述符不是可定位文件”“长度不可靠”“Range 格式不被 WebView 接受”统一归为 416；从用户界面只会看到音源错误，无法区分真实原因。这是下一步必须补齐的诊断能力。

## 建议的修复方向（待你确认后实施）

| 方案 | 可靠性 | 代价 | 说明 |
|---|---:|---:|---|
| **A. 原生 ExoPlayer/MediaPlayer 负责本地播放** | 最高 | 较高 | WebView 只保留 UI 与控制；Android 原生播放器直接使用 MediaStore/SAF URI，定位交给系统媒体栈。这是长期正确架构。 |
| **B. 只对不可定位 URI 临时物化到应用缓存文件，再以 `RandomAccessFile` 提供 Range** | 高 | 中等 | 扫描仍是扫描，不导入用户音乐库；仅在播放该文件时建立临时私有缓存，确保 WebView 获得真正可定位的普通文件。 |
| **C. 给现有代理加真机诊断，按 URI 能力分流** | 必要前置 | 低 | 记录 URI、文件描述符长度、`position()` 异常、Range 头、响应码和网页音频错误码；先确认你的设备属于哪一类失败。 |
| D. 继续网页层重试或调整进度条 | 低 | 低 | 已证明不足，不能解决底层不可随机访问问题，不建议继续投入。 |

## 建议的下一步

建议先实施 **C（诊断）**，在你的真机上复现一次后得到确切错误类型；随后优先实施 **A（原生本地播放）**。如果希望在不大规模改造播放器的前提下尽快稳定，可先实施 **B（不可定位 URI 的临时私有缓存）**。

这三种方案中，只有 A 能从根本上消除“WebView 代理本地 `content://` 音频”的架构风险。请确认优先选择 **A、B，或先 C 后 A/B**，我再开始修复。
