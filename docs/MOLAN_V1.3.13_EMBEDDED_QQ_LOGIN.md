# Molan Light Music 1.3.13 交付说明

**版本**：1.3.13（versionCode 17）  
**安装包**：`molan-light-music-v1.3.13.apk`  
**SHA-256**：`ce073c295943578c97451a131d95167b2fde225b8d3fa4a8ad266462fe6f7f4e`

## 本版目标

本版新增**不依赖用户自建后端**的 QQ 音乐独立登录与歌单读取能力。网易云账号仍使用原有 NCMC 服务；QQ 登录二维码、会话交换、账户资料与“我的歌单”读取均由 APK 的 Android 原生层完成。QQ 音源、封面与 LRC 则通过用户配置的 ChKSz API Key 按 QQ MID 解析。

> QQ 登录和歌单读取使用 QQ 音乐 Web/客户端的非公开网络交互，可能因上游策略、地区、账号状态或反自动化措施而变化。本功能仅用于用户本人登录自己的 QQ 账号，读取自己的歌单，并播放具有合法收听权限的内容；不提供会员、付费或版权限制绕过能力。

| 能力 | 1.3.13 行为 |
|---|---|
| QQ 登录 | 在“我的”页点击 **QQ 登录**，使用 QQ App 扫描并确认二维码。 |
| QQ 会话保存 | QQ Cookie 不传入网页 JavaScript；仅在 APK 私有存储中以 Android Keystore AES-GCM 加密保存。 |
| QQ 我的歌单 | 登录后在“我的歌单”统一列表显示，标注“QQ音乐”。 |
| 网易云我的歌单 | 保留原有列表，标注“网易云音乐”。 |
| QQ 歌单详情 | 使用 `qq:{dissid}` 命名空间缓存，曲目保留 QQ MID。 |
| QQ 播放与歌词 | 通过 ChKSz `/api/qq_music?mid=...` 获取 URL、封面及 LRC；映射当前统一音质设置。 |
| QQ 下载 | 与在线播放使用同一 ChKSz 解析与统一音质设置，继续交给系统下载管理器。 |
| 本地优先播放 | QQ 曲目依旧进入现有本地匹配逻辑；匹配到本地文件时优先使用 Android 原生本地播放器。 |

## 许可证边界

实现过程仅参考了 QQMusicApi 的能力模型和 musiche 的二维码/Cookie 状态机；**没有复制 QQMusicApi 的 GPLv3 源码**。QQMusicApi 自身是 GPL-3.0-or-later 的 Python 库，因此不被打包进 APK，也不作为客户端依赖。musiche 为 Apache-2.0，作为交互流程参考使用。[1] [2]

## 验证结果

| 检查项 | 结果 |
|---|---|
| Java 原生层编译 | 通过；含 Keystore 加密会话、QQ QR 桥接、歌单详情桥接。 |
| JavaScript 语法检查 | 通过；API、下载、播放器与主控制器均通过检查。 |
| QQ ChKSz 参数映射 | 通过；`lossless → flac`，其余档位映射至 ChKSz 支持的 `128k/320k/flac/hires`。 |
| 歌单缓存隔离 | 通过；QQ 使用 `qpl:v1:`/`qpldetail:v1:`，与网易云缓存键隔离。 |
| 本地匹配与扫描缓存回归 | 通过；既有本地曲目匹配和扫描结果持久化测试均通过。 |
| APK 签名 | 通过 Android APK Signature Scheme v2 验证。 |
| APK 内容核验 | 已确认 `QQMusicSession`、`qqQrCreate`、`qqPlaylistDetail`、Keystore 字符串及前端 QQ 资源均已嵌入。 |

## 真机验收步骤

首先安装 APK，并在“我的 → 设置”填写有效的 **ChKSz API Key**。然后在“我的”页面点击 **QQ 登录**，用 QQ App 扫描二维码并确认。成功后，“我的歌单”应直接出现带“QQ音乐”来源标识的歌单，并与已登录网易云账号的歌单共同展示。

打开任意 QQ 歌单，确认曲目列表可载入；点击曲目后，应显示 `QQ · …` 音质状态，封面和歌词在 ChKSz 正常响应时加载。再在歌曲操作菜单选择下载，确认任务进入固定的 `Music/Molan Light Music` 系统目录。最后退出并重新打开应用，QQ 账户信息应能恢复；使用 QQ 登录弹窗中的“退出 QQ 登录”后，QQ 歌单应不再显示。

如果二维码生成、扫码确认或歌单读取失败，请记录弹窗的精确状态文字和 Android 版本。由于此路径依赖 QQ 非公开登录交互，真机反馈是最终兼容性验证所必需的。

## References

[1]: https://github.com/l-1124/QQMusicApi "l-1124/QQMusicApi"
[2]: https://github.com/HeHang0/musiche "HeHang0/musiche"
[3]: https://api.chksz.com/docs/qq_music.html "ChKSz QQ 音乐接口文档"
