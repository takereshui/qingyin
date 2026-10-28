# Qingyin v1.3.1

## 网易云官方账号能力补齐

- 网易云账号信息改为通过官方 WeAPI 获取。
- 网易云“我的歌单”目录改为官方接口同步。
- 网易云歌单详情改为官方接口读取，并分批获取歌曲元数据。
- 网易云每日推荐改为官方接口读取并加入本地缓存。
- 登录网易云官方账号后，歌单同步和每日推荐不再依赖 NCMC。

## 音源路线

- QQ 和网易云播放、下载均以 ChKSz 为主路线。
- ChKSz 失败时，网易云才尝试官方登录会话的短时播放地址。

## 验证

- `testDebugUnitTest` 通过
- `lintDebug` 通过
- `assembleDebug` 通过
- `assembleRelease` 通过
- APK 签名校验通过
- versionCode：44
- versionName：1.3.1
