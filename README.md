#  Music Party 
> 一个高颜值的实时在线听歌Web应用。
>
> *本项目参考自 [EveElseIf/MusicParty](https://github.com/EveElseIf/MusicParty)，85%的代码通过vibe coding完成开发。*
> 
> [演示地址](https://music.thornex.uk/)  [视频演示](https://www.bilibili.com/video/BV1LCVJ6QEmk)

***

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2.5-green) ![Vue](https://img.shields.io/badge/Vue.js-3-4FC08D) ![Docker](https://img.shields.io/badge/Docker-Ready-blue)

**Music Party** 是一个开源的、私有化部署的多人实时在线WEB听歌平台。

它允许你和朋友在一个虚拟房间内搜索并点播歌曲，音源来自你自己部署的 **MP 服务**（QQ 音乐曲库）。系统实现了播放进度同步，无论是在 PC 端还是移动端，所有人听到的都是同一秒的旋律。

（本项目并非“破解版”，MP 服务会把歌曲按 id 缓存在自己的服务器上，浏览器直接取音频播放；会员内容需要 QQ 音乐账号的登录态，在 MP 服务端扫码登录即可，下方有说明）

<img width="1778" height="1080" alt="image" src="https://github.com/user-attachments/assets/64d7f5d1-9837-43ab-8c1b-dad78361b348" />

## 核心特性

*   **单一自建音源（MP）**：全平台只保留一个音源——你自己部署的 MP 服务（QQ 音乐曲库），支持搜索、歌单/专辑/歌手导入。
    *   服务端按歌曲 id 缓存音频文件（FLAC 原样送播），同一首歌不会二次从上游拉取。
    *   浏览器直连 MP 服务播放，MusicParty 后端只做元数据解析与队列调度，不代理音频字节。
    *   需要先单独部署 MP 服务，并把 `MP_API_URL` / `MP_PUBLIC_URL` / `MP_API_TOKEN` 指向它。
*   **精准同步**：基于 WebSocket (STOMP) 的状态分发，结合前端追帧，实现播放状态、进度、歌单、歌词的实时同步。
*   **响应式设计**：完美适配 PC 宽屏与移动端；支持媒体会话锁屏控制，移动端推荐『添加到主屏幕』以启用 PWA 后台播放。
*   **房间权限**：支持设置房间密码，或管理员指令实时锁定/解锁房间。
*   **实时互动**：内置聊天室、点赞动效、系统日志广播。
*   **智能队列**：实现“公平随机”算法，防止单人霸榜。
*   **直播音频流**：可以开启直播音频流，使用一个简单的链接来实时收听，用于类似于vrChat等类似场景。

## Docker 部署（推荐）

本项目支持全自动化的 Docker 部署，建议直接拉取构建好的镜像。

### 1. 使用 Docker Compose 一键启动 (最简方案)

下载项目自带的 `docker-compose.yml` 并根据需要修改其中的环境变量。注意 MP 服务不在 compose 内，需要先自行部署。

```bash
# 下载配置
curl -sSL https://raw.githubusercontent.com/pluviiter/MusicParty/main/docker-compose.yml > docker-compose.yml

# 修改配置（填写管理员密码、MP 服务地址与令牌等）
vi docker-compose.yml

# 启动服务
docker-compose up -d
```

### 2. 使用 Docker Run 启动

如果你已经部署好 MP 服务，可以使用以下命令部署主应用：

```bash
docker run -d \
  --name music-party \
  -p 8848:8080 \
  -e ADMIN_PASSWORD=admin123 \
  -e MP_API_URL=http://127.0.0.1:8321 \
  -e MP_PUBLIC_URL=https://home.netr0.com/music \
  -e MP_API_TOKEN=your-token \
  -e BASE_URL=http://localhost:8848 \
  -e APP_AUTHOR_NAME="ThorNex" \
  -e APP_BACK_WORDS="MUSIC PARTY" \
  -e QUEUE_MAX_SIZE=1000 \
  -e QUEUE_HISTORY_SIZE=50 \
  -e PLAYLIST_IMPORT_LIMIT=100 \
  -e CHAT_HISTORY_LIMIT=1000 \
  -v ./music_party/data:/app/data \
  --restart unless-stopped \
  thornex/music-party:latest
```

### 环境变量说明

| 变量名                       | 必填 | 说明                                                                          |
|:--------------------------|:---|:----------------------------------------------------------------------------|
| `APP_AUTHOR_NAME`         | 否  | 页面显示的作者名字，地点在左上角标题后面。默认为 `ThorNex`。                                         |
| `APP_BACK_WORDS`          | 否  | 中间专辑封面后方的装饰性背景字，强制大写。默认为 `MUSIC PARTY`。                                     |
| `ADMIN_PASSWORD`          | 是  | 管理员密码，用于打开管理员面板。                                                            |
| `MP_API_URL`              | 是  | MP 服务的地址（后端访问用），例如 `http://127.0.0.1:8321`。容器与 MP 不在同一网络时填宿主机地址。              |
| `MP_PUBLIC_URL`           | 否  | 浏览器直接取音频的公网地址，例如 `https://home.netr0.com/music`。留空则回退使用 `MP_API_URL`。           |
| `MP_API_TOKEN`            | 是  | MP 服务的访问令牌（`?token=`），必须与 MP 服务端配置的令牌一致。                                      |
| `MP_ENABLED`              | 否  | 是否启用自建音源，默认 `true`。关闭后无法搜索与播放。                                              |
| `BASE_URL`                | 否  | 服务的域名（带协议）。用户获取直播流链接时，拼接在前面。默认为 `http://localhost:8848`。                    |
| `QUEUE_MAX_SIZE`          | 否  | 播放队列最大长度，默认 `1000`。                                                         |
| `QUEUE_HISTORY_SIZE`      | 否  | 播放历史记录保留数量，默认 `50`。当播放列表里没有音乐时，会从历史记录随机抽选。                                  |
| `QUEUE_MAX_USER_SONGS`    | 否  | 单个用户在队列中允许的最大点歌数量，默认 `100`。                                                 |
| `PLAYLIST_IMPORT_LIMIT`   | 否  | 导入歌单时的最大歌曲数限制，默认 `100`。                                                     |
| `CHAT_HISTORY_LIMIT`      | 否  | 聊天历史记录保留数量，默认 `1000`。                                                       |
| `CHAT_MIN_INTERVAL`       | 否  | 聊天发言最小间隔 (毫秒)，默认 `1000`。                                                    |
| `CHAT_MAX_LENGTH`         | 否  | 单条聊天消息最大长度 (字符)，默认 `200`。                                                   |
| `AUTH_RATE_LIMIT_ENABLED` | 否  | 是否开启密码验证频率限制，默认 `true`。                                                     |
| `AUTH_MAX_ATTEMPTS`       | 否  | 密码验证最大尝试次数，默认 `5`。                                                          |
| `AUTH_WINDOW_SECONDS`     | 否  | 密码验证统计时间窗口 (秒)，默认 `60`。                                                     |
| `AUTH_BLOCK_DURATION`     | 否  | 超过尝试次数后的封锁时长 (秒)，默认 `300`。                                                  |

---

## Windows 启动器

如果你想在 Windows 上快速运行，而不想折腾 Docker、Java 或 Node.js 环境，可以使用 **一键启动器**。
<img width="1010" height="713" alt="Snipaste_2026-05-21_10-45-11" src="https://github.com/user-attachments/assets/2402f66e-4a52-423f-a32c-c0478c84a283" />

### 获取与使用
1.  前往 [GitHub Releases](https://github.com/pluviiter/MusicParty/releases) 下载最新的 `MusicParty.exe`。
2.  将 `MusicParty.exe` 放置在你喜欢的文件夹中。
3.  **直接双击运行**：
    *   启动器会自动释放内置的 JRE 环境与 Java 核心程序。
    *   所有的配置、运行环境、数据都保存在 EXE 同级目录下。
4.  在 UI 界面上修改端口、密码、MP 服务地址与访问令牌等配置，点击“启动系统”即可。
5.  系统就绪后，点击界面上的“打开网页”即可。
6. 如果你需要公网部署，请保持监听地址为0.0.0.0，并使用你的公网IP:端口的形式访问网页。（推荐使用Cloudflare Tunnel来进行内网穿透）
7. 更新时，删除bin文件夹，覆盖exe文件后再启动。

---

### 移动端
在移动端环境中，由于对浏览器的限制，在后台播放或锁屏播放时，经常会出现断联，失去同步等情况
为了解决这个方法，安卓端可以下载Release中的APK（本质套壳浏览器），苹果则可以尝试在浏览器中将网页添加到桌面。

---

## 房间密码
<img width="622" height="427" alt="Image_2026-05-21_10-53-45_s10ixfb1 qoj" src="https://github.com/user-attachments/assets/1f5eeb44-4a21-4dc5-8399-753b9f16b88a" />

部署后首次启动需要设置房间密码，其他成员只有使用密码才能进入。
也可以设置为public公开，此时无需密码即可进入。
密码可以在管理员面板中更改。

---

## 用户歌单
可以通过搜索歌手/歌单名进行绑定，以便查看该歌手或歌单的内容，并一次性导入到播放列表。
注意登录态（QQ 音乐账号）由 MP 服务端统一维护，客户端不需要也无法填写 Cookie；能否看到会员/隐藏内容取决于 MP 服务端的登录状态。

---

## 聊天框命令

在前端**聊天窗口**中可以输入以下命令：
*   `//clear`: 从播放队列中清空自己点播的所有歌曲。
*   `//stream`: 获取自己的直播流链接（需要开放直播流）。
*   `//admin`: 打开管理员控制面板（需要管理员密码）。

---

## 随机播放
随机播放是本项目的一个重点，为了保证所有成员的参与度，当开启随机播放时，将按照每个成员一首的方式轮替随机播放此成员点的歌。
如果希望完全随机，可以在管理员面板中切换，此外，也可以设置随机时是否会随机到不在线成员的歌曲。

---

## 投票切歌
管理员面板可以开启投票切歌，开启后，只有当选择切歌的人数超过设置的阈值比例后才会切歌，此外，也可以设置在歌曲播放的前多少秒内无法投票。

---

## 置顶
在按成员轮替的随机播放中，播放列表会按成员分组，此时置顶逻辑有所变化。第一次点击置顶后，歌曲只会在该成员的列表中置顶，即轮替到该成员时，此歌曲优先播放。
对已经置顶的歌曲再次点击置顶，才会全局置顶，即无视轮替，下一首 必定是指定歌曲。

---

## 点赞
播放时，点击中间的封面可以对当前歌曲点赞，会有对应的视觉效果。
在搜索页面的LIKESONG选项卡下可以看到所有点赞的歌曲，可以重新添加到播放列表或者前往源页面。
注意记录的点赞歌曲是本地保存的，清理浏览器缓存丢失记录。

---

## 管理员面板
在聊天窗口输入//admin并输入管理员密码后，可以进入管理员面板修改配置。
<img width="1254" height="823" alt="Image_2026-05-21_16-14-47_uvmklady ree" src="https://github.com/user-attachments/assets/9ea3ffac-0db0-434d-a972-642a8cec89bd" />

* 修改部署时的配置参数。
* 锁定播放，切歌，随机按钮以防止用户滥用。（建议保持播放按钮锁定，防止某一个用户因为卸下耳机等行为导致的自动暂停）
* 随机播放与投票切歌相关配置。
* 开关直播流功能。
* 启停自建音源 (MP)。
* 对播放列表或者聊天记录进行清理。
* 重置系统。

---

## 历史记录
播放过的歌曲会被加入历史记录，当没有歌曲播放时，会随机从历史记录中播放歌曲。

---

## 闲置
当没有任何成员连接时，播放会自动暂停。如果该状态保持20分钟，当前歌曲播放状态将被清空。

---

## 直播流链接获取
1. 确保已经管理员已经在管理员面板启用了直播流。
2. 在聊天窗口中输入`//stream`
3. 切换到系统日志窗口，即可看到自己的直播流链接。
#### 注意，直播流使用FFmpeg，会占用更多性能，且有大量流量消耗。

---

## MP 服务端凭证与令牌

客户端的 Cookie 配置项已经全部移除，登录态与令牌都由 MP 服务端负责：

* **QQ 音乐登录（扫码）**：在 MP 服务端执行扫码登录（`qq-backend/mp_fetch.py login-qr`，用 QQ 音乐 App 扫码），登录结果会持久化到服务端的凭据文件（`provider.json`），之后所有歌曲解析与下载都用这份登录态。登录过期后需要重新扫码。
* **访问令牌**：MP 服务要求所有 `/mp/*` 请求带 `?token=`，令牌取自服务端配置（环境变量 `MP_API_TOKEN` 优先）。把同一个值填到 MusicParty 的 `MP_API_TOKEN`（Windows 启动器里是"访问令牌"）即可，两边不一致会直接播放失败。
* **缓存**：音频文件由 MP 服务端按歌曲 id 缓存，超出配置上限时按 LRU 淘汰；MusicParty 自己不再维护任何本地音频缓存。

---

## 本地开发指南

### 前端 (music-party-web)

1.  环境要求：Node.js 18+
2.  进入目录并安装依赖：
    ```bash
    cd music-party-web
    npm install
    ```
3.  启动开发服务器：
    ```bash
    npm run dev
    ```
4.  配置代理：`vite.config.js` 默认将 `/api` 和 `/ws` 代理到 `http://localhost:8080`。

### 后端 (Java)

1.  环境要求：JDK 21, Maven 3.x, 并已部署可访问的 MP 服务。
2.  配置：修改 `src/main/resources/application.yml`，或通过环境变量传入 `MP_API_URL` / `MP_PUBLIC_URL` / `MP_API_TOKEN` 等配置。
3.  运行：
    ```bash
    mvn spring-boot:run
    ```

### 完整构建

建议直接使用 Docker 镜像进行生产环境运行。构建镜像请参考根目录下的 `Dockerfile`。

---

## 免责声明

*   本项目仅供学习交流使用，请勿用于商业用途。
*   本项目涉及的上游音乐接口（QQ 音乐）为非官方接口，项目开发者不对接口的可用性及账号风险负责。
*   请尊重版权，支持正版音乐。

---

## 贡献

欢迎提交 Issue 和 Pull Request！无论是修复 Bug、新增功能还是优化文档。

## License

MIT License
