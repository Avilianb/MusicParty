# MusicParty 单一自建音源（MP）改造设计

日期：2026-09-14
状态：已确认关键决策（API 承载=扩展现有 panel；缓存=FLAC 原样送播；范围=完全替换；鉴权=token 直连；凭据=扫码登录；launcher 一起改；部署=服务器 Podman 容器）

## 1. 目标与非目标

目标：MusicParty 只保留一个音源——自建服务器（home-root 上的 amdl-panel）的 QQ 音乐 API。歌曲音频按 id 在服务器硬盘上缓存，同一个 `mid` 永不二次从 QQ 下载；浏览器直连该服务器播放 FLAC。

非目标：不做转码/多码率自适应；不做多用户凭据（只有一份服务器级 QQ 凭据）；不保留网易云/Bilibili/私人FM-DJ 的任何兼容层。

## 2. 架构

```
浏览器 <audio src>  ──GET /music/mp/audio/{mid}?token──▶ nginx ─▶ panel(Express:8321)
      ▲                                                              │ 命中
      │ WS: PlayerState.nowPlaying.music.url                        ▼
MusicParty 后端 ──/mp/search /mp/song /mp/prepare /mp/lyric──▶ /data/mp-cache/{mid}.flac
      │                                                              ▲ 未命中
      └── ffmpeg 直播流直接拉 /mp/audio/{mid}                  qq-backend(venv): fetch purl + 下载
```

- MusicParty 后端只做"元数据解析 + URL 组装 + 队列调度"，不代理音频字节。
- 音频 URL 由后端拼成绝对地址后经 STOMP 推给浏览器；该 URL 同时被服务器侧 ffmpeg（直播流）直接消费。
- 平台 id 冻结为 `qq`（唯一 `IMusicApiService` 实现）。

## 3. 服务器侧契约（panel `/mp/*`）

鉴权：`?token=`（`<audio>` 无法自定义 header）；也接受 `X-MP-Token`。token 取自 `provider.json.mp.token`，环境变量 `MP_API_TOKEN` 优先；token 为空时**拒绝全部** `/mp/*`（503），不开放。

| 端点 | 响应 |
|---|---|
| `GET /mp/search?term=&entity=song\|album\|artist\|playlist&limit=20` | `{results:[{id,type,title,artists[],album,cover,durationMs,url,trackCount}]}` |
| `GET /mp/song/{id}` | 单曲同构 + `cached:bool` |
| `GET /mp/prepare/{id}` | `202 {status: pending\|downloading\|ready\|failed}`（幂等预热，不阻塞） |
| `GET /mp/audio/{id}` | 200/206 音频（`Accept-Ranges`、`Content-Type` 按扩展名）；未命中则先下载完再回（上限 `requestTimeoutSeconds`，超时 504） |
| `GET /mp/lyric/{id}` | `text/plain; charset=utf-8` LRC（无则空体） |
| `GET /mp/user/search?term=` | `{results:[{id,type:artist\|playlist,name,avatar,trackCount}]}` |
| `GET /mp/list?kind=auto\|album\|artist\|playlist&id=&offset=&limit=50` | `{id,name,cover,total,songs[]}` |
| `GET /mp/stats` | `{files,bytes,limitBytes,pending}` |

id 约定：`pl:<numeric>` 歌单、`al:<albumMid>` 专辑、`ar:<singerMid>` 歌手；单曲 id 即 QQ `mid`。`kind=auto` 按前缀分派。

缓存：`/data/mp-cache/`，文件 `{mid}.{flac|mp3}` + 元数据 sidecar `{mid}.json` + 可选 `{mid}.lrc`。启动扫描重建索引；`mp.cacheMaxSize`（默认 20GB）超出按 LRU 淘汰；同一 mid 并发请求 in-flight 去重；下载并发 `mp.concurrency`（默认 1）、每个任务间隔 ≥3s（防风控）。

Python 侧：`qq-backend/mp_fetch.py`，子命令 `search|song|fetch|list|lyric|login-qr|login-poll|status`；音质降级链复用 `quality.py`；下载核心与现有 `qqmusic.py download` 共用同一实现（抽公共模块，不新增第二套）。

凭据：`mp_credential.py` 统一负责读写 `provider.json` 的 cookie、扫码登录结果持久化、`refresh_credential` 续期与过期检测；`/mp/stats` 暴露 `credential: ok|expiring|expired`。

## 4. MusicParty 后端改动

新增：`service/api/MpMusicApiService.java`（platform `qq`），注入 `WebClient` + `AppProperties`；`AppProperties.MpApiConfig{baseUrl,publicBaseUrl,token,enabled,timeoutSeconds}`。

删除：`NeteaseMusicApiService`、`BilibiliMusicApiService`、`BilibiliWbiService`、`BilibiliCookieService`、`BilibiliApiUtils`、`PrivateDjService`、`LocalCacheService`、`LocalResourceConfig`、`CacheStatus`、`PrivateDjMode`、`PlatformType`、`DownloadStatusEvent`、`PrivateDjSegment`、`AdminPrivateDjUpdateRequest`、`AdminCookieRequest`，以及 `PlayableMusic.needsProxy`、`IMusicApiService.prefetchMusic`、`QueueItemStatus.{PENDING,DOWNLOADING,PLAYING}`。

`MusicPlayerService`：删 FM/DJ 编排（`shouldPlayPrivateFmDj`/`playFmDjNext`/`playFmMarkerNext`/`isFmMarker`/`syncFmMarker`/`loadFmDjPlayable`/`applyFmDjSegment`/`handleFmDjError`/`currentIsVoice`/回退计数字段）、`handleDownloadEvent`、缓存状态映射；`initialStatus` 恒为 `READY`；保留队列调度、版本号、广播、投票切歌、闲置、直播流。

持久化迁移：`QueuePersistenceService` 加载时把历史遗留 `PENDING/DOWNLOADING/PLAYING` 归一化为 `READY`，并丢弃 `platform != qq` 的队列/历史项（避免旧枚举与死源导致整个文件静默加载失败）。

## 5. 前端（music-party-web）

平台 chip 收敛为单一 `qq`；删除 B 站时长上限（`isUnplayable`/10MIN badge/`bilibiliMaxDurationMinutes`）、私人电台/DJ 管理面板与 API、`openSourcePage`/`openLikedSource` 改为使用接口返回的 `url`（QQ 歌曲页）；`usePlaylistLogic` 去掉按平台分页分支；`socketHandler` 不再重放已删平台的绑定；`player.js` 的 config 字段收敛为 `mpEnabled`；点赞与绑定只保留 `qq` 条目；删除 `vite.config.js` 的 `/media`、`/proxy` 代理与 `components/useSearch.js`（死文件）。

## 6. Launcher / 基础设施

`launcher/`：删除内置 NeteaseCloudMusicApi 的进程管理与 `neteaseCookie/neteaseQuality/neteaseEnabled/biliCookie/bilibiliEnabled/cacheMaxSize` 配置项，改为 MP base-url/token/enabled。`docker-compose.yml`、`Dockerfile`、`.github/workflows/build-release.yml`、`build-local.ps1`、`README.md`、`.gitignore`（`/cached_media/`）、`android/.../MediaSessionManager.kt`（网易云 referer）同步清理。

部署：home-root 上用 Podman 起 MusicParty 容器（Spring Boot 单 jar 含前端静态资源），nginx 反代到域名路径；容器内经 `http://127.0.0.1:8321` 访问 panel，浏览器侧使用 `MP_PUBLIC_URL=https://home.netr0.com/music`。

## 7. 验证

1. panel：`node --test panel/test/`（token 门、Range/206、LRU 淘汰、并发去重、search 透传，python 侧以注入的 spawnFn 打桩）。
2. curl 实测：`/mp/search` → `/mp/song` → `/mp/prepare` → `/mp/audio`（首次落盘、二次命中、Range）。
3. Java：`mvn -q test` 全绿 + 应用启动。
4. 端到端：浏览器搜索→入队→播放，确认 `<audio>` 出 FLAC、`/data/mp-cache` 落文件、复播无新 QQ 请求（对比 mtime 与日志）、`/radio/stream` ffmpeg 可读。
5. 容器部署后在域名上复跑第 4 步。

## 8. 风险与既定取舍

- 带宽：FLAC 25–50MB/首 × 同时在线人数，家宽上行是硬上限（用户已选择无损原样送播）。
- 冷启动延迟：首次点播需等 QQ 下载完成；用 `/mp/prepare` 在入队时预热缓解。
- 风控：QQ 会 `RatelimitedError`；靠缓存 + 并发 1 + 3s 间隔缓解。
- token 会出现在 `<audio src>`、STOMP 负载、nginx 日志中；仅作为"防白嫖"而非强认证。
- 失去本地缓存兜底：MP 服务器不可达时该曲直接失败并跳过（保留原有 skip-on-error 路径）。
- 旧 `data/queue-data.json` 与浏览器 localStorage 的旧平台数据按第 4/5 节迁移策略处理，不做兼容层。
