# QQ 登录与 APlayer 实现说明

## QQ 扫码登录诊断

2026-08-12 的被动网络检查确认，`https://ssl.ptlogin2.qq.com/ptqrshow` 仍可返回二维码 PNG 与 `qrsig` Cookie；首个 `ptqrlogin` 轮询返回 `ptuiCB('66', ...)`，即等待扫码。当前 Android 实现将二维码登录成功后的回调 URL 直接用于 Cookie 收尾，并仅依赖响应头 Cookie；这与参考实现所采用的“解析成功回调中的 UIN/ptsigx，显式请求 `check_sig`，再从 QQ 音乐 `QQLogin` JSON 响应中解析 `musicid` 与 `musickey`”的链路不一致，因而会导致用户确认后无法建立可用 QQ 音乐会话。

参考项目 `L-1124/QQMusicApi` 标示为 GPL-3.0。只能用于互操作行为比对，不能把其源码复制、合并或改写后并入当前私有仓库。

## APlayer 行为与许可证

APlayer 官方文档说明：当播放器包含多首音频时应提供可展开的播放列表，并通过 `listFolded` 与 `listMaxHeight` 控制初始折叠和最大可视高度。实现将采用该交互原则：底部弹出队列、当前曲目高亮、点击任意条目切换、支持移除非当前曲目。

待通过 GitHub API 复核 APlayer 的许可证后，仅在许可证允许范围内引入代码；如果直接复用，会在仓库中保留相应许可证和声明。无论是否直接复用，播放模式将按清晰的 APlayer 风格语义实现：列表循环、单曲循环与随机循环，并维护随机播放历史以保证“上一首”可回退。

## 来源

1. QQMusicApi：<https://github.com/L-1124/QQMusicApi>（GPL-3.0，仅用于互操作审计）
2. APlayer 文档：<https://github.com/DIYgod/APlayer/blob/master/docs/README.md>
3. APlayer 仓库：<https://github.com/DIYgod/APlayer>
