# QQ 音乐接口能力核查

## 已核对来源

- ChKSz QQ 音乐文档：<https://api.chksz.com/docs/qq_music.html>
- QQ 音乐开发者平台 OpenAPI：<https://developer.y.qq.com/docs/openapi>

## ChKSz `qq_music` 端点实际能力

ChKSz 文档只公开一个 `GET /api/qq_music` 解析端点，要求个人 `apikey`。它支持按关键词搜索、按 QQ 歌曲 `mid` 解析，以及音质参数 `128k`、`320k`、`flac`、`hires`、`master`。结果提供歌曲名、歌手、专辑、封面、LRC、URL 和码率。

该文档明确说明 `cookie` 参数仅为旧调用兼容保留，**当前音乐源不会使用或转发该值**。文档中没有 QQ 账号二维码登录、账户资料、我的歌单、喜欢列表或私人推荐端点。

## 官方 QQ 音乐 OpenAPI

官方平台列出登录鉴权、歌单与歌曲能力，但接入要求是联系 QQ 音乐商务、注册开发者账号、审核后获取 `app_id` 和 `app_key`；页面同时写明暂不支持个人开发者申请。播放和用户歌单能力还受合作模式、签名、频率及登录授权约束。

## 结论

当前 Molan 已有的 ChKSz API Key 设置可以安全扩展为 QQ 音乐的“搜索/解析/播放/歌词”备用来源，但**不能在没有额外服务或官方合作凭据的情况下实现真实 QQ 登录和读取用户的我的歌单**。

要实现用户要求的“网易云 + QQ 双登录、我的歌单合并”，需要以下其一：

1. 用户提供已审核的 QQ 音乐官方 OpenAPI `app_id` 与服务端签名服务。密钥不能放进 APK，必须由用户可控后端签名。
2. 用户提供一个自己部署、明确授权且提供 QQ 登录与用户歌单端点的后端 API；需要提供 API 地址与认证说明。
3. 在当前 ChKSz 能力下先实现 QQ 音乐备用搜索/解析/播放/歌词，并把“QQ 我的歌单”保留为手动导入公开歌单链接的功能；这不是 QQ 登录。

## 社区服务候选补充

### `ylw1997/qqmusic-api`

仓库地址：<https://github.com/ylw1997/qqmusic-api>。该仓库当前是 API 文档与 Python 测试客户端，README 列出了 QQ/微信扫码登录二维码、登录状态轮询、用户资料、我喜欢和我的歌单接口；用户相关能力依赖登录后取得的 `musicid` 与 `musickey`。仓库创建时间很近、提交数较少，且 README 显示扫码脚本为本地测试命令，不是可直接部署并供 Molan 调用的 HTTP 服务。因此它可作为协议/后端实现参考，但不宜直接作为生产依赖。

### `Yyyangshenghao/simple-music` QQ 服务设计

文档地址：<https://github.com/Yyyangshenghao/simple-music/blob/master/docs/qq-music-api.md>。文档描述一个自建服务层，具有 `/api/qq/login/status`、手动 Cookie 注入、`/api/qq/user/playlists`、歌单详情、歌曲 URL、歌词与推荐等本地路由。其上游依赖 QQ 音乐 Web/客户端逆向接口；文档明确指出 QQ 音乐无面向个人开发者的公开 OpenAPI，二维码登录依赖 `mu.y.qq.com` MQTT 长连接，复杂度较高，并建议当前以手动 Cookie 登录为实现路径。该方案的架构最接近 NCMC：**需要用户自建并控制一个后端**，而不是把 Cookie 或逆向签名放进 APK。

### `jsososo/QQMusicApi`

文档地址：<https://jsososo.github.io/QQMusicApi/>。这是 Express + Axios 的自建 Node 服务，覆盖 Cookie 设置、用户资料、用户创建/收藏歌单、歌单详情、搜索、歌曲链接和歌词。文档同时明确要求用户手动从 QQ 音乐网页获取 Cookie，警告公共 Cookie 有封号风险，声明项目仅供学习参考，并建议自行部署。这能作为“手动 Cookie + 自建后端”的候选，但项目维护年代较早，不能使用其公共测试端点，也不能将用户 Cookie 交给第三方公共服务。

## 当前推荐

若要做类似 NCMC 的 QQ 能力，最稳妥的社区路线是部署一个**用户自己控制**的 QQ API 服务，优先采用具备 Cookie 登录状态、`user/playlists`、歌单详情、歌曲 URL 和歌词路由的服务模型；Molan APK 只保存该服务地址和会话，不直接连接 QQ 非公开 Web 接口。二维码登录可作为后端后续能力，第一版应优先支持用户自行从 QQ 音乐网页导出的 Cookie 手动粘贴。所有社区方案都依赖非公开接口，可能被上游变更或风控影响，且不适用于商业/公开大规模服务。

## 许可证与维护核验（2026-08-12）

GitHub 元数据核验显示 `Yyyangshenghao/simple-music` 与 `jsososo/QQMusicApi` 均为 GPL-3.0；两者不可直接复制、链接进闭源或非 GPL 的 Molan APK。前者近期仍有更新，但应仅作为自建服务接口设计的参考，或在用户自行决定以 GPLv3 方式部署完整服务时使用。`ylw1997/qqmusic-api` 未声明许可证，不能将其代码纳入项目；可仅阅读公开文档来理解登录所需的会话字段。

## 与现有线路的能力对照

| 能力 | 当前 NCMC（网易云） | ChKSz QQ 解析 | 自建 QQ 社区服务模型 |
|---|---|---|---|
| 用户登录 | 已有 QR 登录与 Cookie 备用登录 | 不支持；`cookie` 不参与 QQ 解析 | 通常支持手动 Cookie；二维码登录属于可选的复杂后端能力 |
| 当前用户资料 | 已支持 | 不支持 | 可支持，依赖 QQ 会话票据 |
| 我的歌单 | 已支持、可缓存 | 不支持 | 可支持创建/收藏歌单及喜欢列表 |
| 歌单详情 | 已支持 | 文档未提供 | 可支持 |
| 搜索/播放/歌词 | 已支持 | 支持搜索、MID 解析、URL、封面与 LRC | 通常支持，但播放权限受账号/版权限制 |
| APK 内直接接入 | 已接入 | 可以 | 不建议：需要隐藏 Cookie、签名与非公开协议细节 |
| 稳定性/合规风险 | 依赖 NCMC 服务 | 依赖 ChKSz 服务 | 较高：非公开接口可能变更、触发风控 |

## 推荐实施路径

建议把 QQ 服务做成**与 NCMC 相同的可配置后端**，而不是把逆向协议、Cookie 或二维码轮询塞入 Molan APK。后端最小契约应提供：`login/status`、可选的 `login/qr/create`/`login/qr/check`、`user/profile`、`user/playlists`、`playlist/detail`、`song/url`、`lyric` 与 `search`。Molan 只保存后端地址与从后端返回的会话标识，统一列表按 `source: 'netease' | 'qq'` 做 namespaced 缓存键与来源徽标。

基于当前证据，首版建议先实现“**自建 QQ 后端 + 用户手动 Cookie 登录 + 我的歌单合并**”；等后端稳定后，再单独加入 QR 登录。这能最快满足歌单聚合，同时避免在 APK 内处理 QQ Cookie 和 MQTT 扫码长连接。ChKSz 继续作为无登录 QQ 搜索/解析备用线路，不能承担 QQ 账号系统。

## 用户指定的 `l-1124/QQMusicApi`

仓库：<https://github.com/l-1124/QQMusicApi>；文档：<https://l-1124.github.io/QQMusicApi/>。该项目是异步 Python QQ 音乐 API **库**，可通过 `pip install qqmusic-api-python` 安装；截至调研时文档版本为 v0.7.2。它不是已部署的 HTTP API 服务，Molan APK 不能直接以 REST 地址调用它，必须由用户自己部署一个后端适配层，负责登录会话与把歌单资料转换为受控 JSON 接口。

仓库采用 GPL-3.0-or-later，且 README 明确仅用于技术研究、非商业用途。因而不能将该库源码复制、链接或打包进现有 Molan APK；如果用户选择自行部署该项目或基于它建立服务，必须接受并遵守其 GPLv3 对部署/分发代码的要求，并仅在个人、合法、尊重版权的范围内使用。

## `HeHang0/musiche` 调研结论

仓库：<https://github.com/HeHang0/musiche>。该项目使用 Apache-2.0 许可证，README 声称支持网易云、QQ 音乐和咪咕音乐的账号、个人歌单和二维码登录。与 GPL 项目不同，Apache-2.0 在保留版权与许可证声明的前提下更适合借鉴实现；但仍不应把其完整应用或未经审计的 QQ 网络代码直接混入 Molan。

源码审查显示其 QQ 模块位于 Web 前端 `web/src/utils/api/qq.ts`，使用 QQ Web 非公开接口完成二维码轮询、会话 Cookie 汇集、资料读取和 Cookie 刷新；会话字段包括 `uin`、`qm_keyst`/`qqmusic_key` 及若干刷新票据。它的 QR 登录流程会跨 `ptlogin2.qq.com`、`graph.qq.com` 和 `u.y.qq.com`，需要处理重定向与 Set-Cookie。其 QQ 歌单详情读取也由前端直接请求 QQ 上游接口。

仓库中的 `proxy-server` 是一个极小 Go HTTP 转发器（监听 8737 并将请求交给 `ProxyHandler`），不是账户会话存储或 QQMusicApi 的 REST 服务。因此 musiche 可以提供 QQ QR/Cookie 会话的架构参考，但无法作为“直接填写地址即可用”的 QQ 账户后端。若接入 Molan，应独立建立最小 QQ 会话后端，安全保存 Cookie，并只向 APK 暴露标准化的账户/歌单 JSON。

## `l-1124/QQMusicApi` 源码能力核验

只读检查其模块结构和登录标识后，确认库包含 `login.py`、`login_utils.py` 及歌单相关模块。登录实现覆盖 QQ QR、微信 QR、二维码状态轮询，并包含 QQ 登录重定向流程及部分微信登录的 MQTT 状态处理。这说明它在功能上能成为 QQ 双登录后端的底层库。

但它仍是 Python 库而非 HTTP 服务。正确接入方式是：部署一个独立后端，让后端调用该库并把二维码图像、轮询状态、用户资料、歌单和歌单详情转成 Molan 所需的 REST JSON；APK 只能访问该后端，不能直接安装该库或长期持有 QQ Cookie。由于库使用 GPL-3.0-or-later，自建后端如分发、公开或修改其代码时需要遵守对应许可证义务。

## QQMusicApi 与 musiche 对比

| 维度 | QQMusicApi | musiche |
|---|---|---|
| 定位 | 异步 Python QQ 音乐 API 库 | 跨平台音乐应用，含前端 QQ 逆向实现和轻量 HTTP 转发器 |
| QQ QR 登录 | 库内具备 QQ / 微信二维码和状态轮询能力 | 前端模块具备 QQ 二维码、重定向和 Cookie 汇集能力 |
| 用户歌单 | 具备相应底层模块，可由后端封装 | 应用级能力声明完整，但代理服务并不负责账户会话 |
| 后端现成程度 | 需自行包装 REST 服务和持久会话 | 需自行抽取并重构前端 QQ 模块为后端服务 |
| 许可证 | GPL-3.0-or-later | Apache-2.0 |
| 主要风险 | GPL 分发义务；依赖非公开 QQ 接口 | 非公开 QQ 接口、前端 Cookie 暴露风险；直接移植会使 APK 持有会话 |
| 适用性 | 更适合个人自建的 QQ 会话后端底层 | 更适合作为 QR/Cookie 工作流和字段模型参考 |

## 建议的组合

将 QQMusicApi 部署在用户可控后端，提供统一 REST 接口；借鉴 musiche 的 QR 登录状态机、Cookie 刷新字段和 QQ 曲目/歌单字段归一化，但不直接将 QQ 逆向网络逻辑放进 APK。客户端使用 ChKSz 的 `/api/qq_music` 按歌曲 MID 获取播放 URL、封面和 LRC，从而将“账户/歌单信息”和“音源解析”职责分离。后端应只暴露加密会话 ID，绝不把原始 QQ Cookie 返回或存进 APK。
