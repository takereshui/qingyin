# ChKSz QQ 音乐接口集成结论

来源：<https://api.chksz.com/docs/qq_music.html>，于 2026-08-12 查阅。

| 能力 | 接口与参数 | 实现结论 |
|---|---|---|
| QQ 搜索 | `GET /api/qq_music?msg=<关键词>&num=<1-50>&type=json&apikey=<密钥>` | 返回 `list`，每条包含 `n`、`name`、`singer`、`album`、`mid`；可转为应用内 `Track` 列表。 |
| QQ 音源解析 | `GET /api/qq_music?mid=<MID>&size=<质量>&type=json&apikey=<密钥>` | 仅以 `mid` 解析；返回 `url`、`cover`、`lrc`、`bitrate`、`format` 等。 |
| QQ 质量集合 | `128k`、`320k`、`flac`、`hires`、`master` | 与网易云 `standard`、`higher`、`exhigh`、`lossless`、`hires`、`jymaster` 不相同，必须定义独立枚举和请求参数，不能映射为同一 wire value。 |
| 认证 | `apikey` 必填 | 不需要 QQ 登录，也不应传递 QQ Cookie；继续使用应用设置中已存在的 ChKSz API Key 字段。 |
| 限制与异常 | 401 无效密钥；403/429 受限；502/504 上游超时 | UI 应明确显示服务端错误并避免静默降档。 |

下载策略：QQ 与网易云均应请求用户明确选择的各自平台档位；返回的实际 `bitrate` / `format` 必须写入曲目和下载任务。接口不返回目标档位或格式不符合时取消下载，而不是改为较低档位后仍标为原选项。

歌单说明：当前 ChKSz QQ 接口文档仅列明单曲搜索与 MID 解析，未给出 QQ 歌单详情接口。因此“QQ 歌单导入”应先提供文本/链接/MID 列表导入入口，或在后续确认额外接口后再启用可解析的链接导入，不能假设该公开接口具备歌单端点。
