# Qingyin v1.3.0

## 网易云官方登录与音源

- 移植 Q-music 的网易云官方登录会话与 WeAPI 请求逻辑。
- 新增网易云官方 WebView 登录入口，登录会话仅保存在本机。
- 恢复网易云歌曲搜索，并支持网易云歌曲 ID 识别。
- 网易云官方播放地址支持标准、320K、无损和 Hi-Res 音质降级。
- 网易云官方 URL 仅作为 ChKSz 失败后的备用线路。

## 音源路线调整

- QQ 音乐播放和下载继续以 ChKSz 为主路线。
- 网易云音乐播放和下载改为以 ChKSz 为主路线。
- ChKSz 不可用时，网易云才尝试官方登录会话获取短时播放地址。
- 播放地址不永久保存，避免复用过期 URL。

## 验证

- `testDebugUnitTest` 通过
- `lintDebug` 通过
- `assembleRelease` 通过
- APK 签名校验通过
- versionCode：43
- versionName：1.3.0
