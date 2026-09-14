package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.Playlist;
import org.thornex.musicparty.dto.UserSearchResult;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * 唯一启用的音乐源：自己服务器上的 QQ 音乐服务（panel 的 {@code /mp/*} 路由）。
 *
 * <p>服务端只做元数据/歌词/预热调用；可播放地址是 {@code <publicBaseUrl>/mp/audio/{mid}?token=...}，
 * 由浏览器直连（服务端已把音频按 mid 落盘缓存，首播可能等几秒到几十秒）。</p>
 */
@Service
@Slf4j
public class MpMusicApiService implements IMusicApiService {

    /** 单一音源，平台标识固定为 qq（前端/持久化数据里的 platform 段即此值） */
    public static final String PLATFORM = "qq";

    private static final int SEARCH_LIMIT = 30;
    private static final int USER_SEARCH_LIMIT = 20;
    private static final int USER_PLAYLIST_LIMIT = 50;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public MpMusicApiService(WebClient webClient, ObjectMapper objectMapper, AppProperties appProperties) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    private AppProperties.MpApiConfig cfg() {
        return appProperties.getMp();
    }

    @PostConstruct
    public void initialize() {
        AppProperties.MpApiConfig mp = cfg();
        if (!mp.isEnabled()) {
            log.warn("自建音源已禁用（app.music-api.mp.enabled=false），所有取歌接口将报错");
            return;
        }
        String base = mp.getBaseUrl();
        if (!StringUtils.hasText(base)) {
            log.error("自建音源未配置 base-url（环境变量 MP_API_URL），取歌将全部失败");
            return;
        }
        log.info("自建音源(MP) 已启用: baseUrl={}, publicBaseUrl={}, token={}",
                base, StringUtils.hasText(mp.getPublicBaseUrl()) ? mp.getPublicBaseUrl() : "(回退 baseUrl)",
                StringUtils.hasText(mp.getToken()) ? "已配置" : "未配置");
    }

    @Override
    public String getPlatformName() {
        return PLATFORM;
    }

    /** 浏览器播放用的音频直链（含 token）：优先 publicBaseUrl，未配置则回退 baseUrl。 */
    public String audioUrl(String musicId) {
        String base = StringUtils.hasText(cfg().getPublicBaseUrl()) ? cfg().getPublicBaseUrl() : cfg().getBaseUrl();
        if (!StringUtils.hasText(base)) {
            throw new ApiRequestException("自建音源未配置，请联系管理员设置 MP_API_URL");
        }
        StringBuilder url = new StringBuilder(trimTrailingSlash(base))
                .append("/mp/audio/").append(urlEncode(musicId));
        if (StringUtils.hasText(cfg().getToken())) {
            url.append("?token=").append(urlEncode(cfg().getToken()));
        }
        return url.toString();
    }

    @Override
    public Mono<List<Music>> searchMusic(String keyword) {
        return getJson("/mp/search", Map.of("term", keyword, "entity", "song", "limit", String.valueOf(SEARCH_LIMIT)))
                .map(root -> mapMusics(root.path("results")));
    }

    @Override
    public Mono<List<Music>> getPlaylistMusics(String playlistId, int offset, int limit) {
        return getList(playlistId, offset, limit)
                .map(root -> mapMusics(root.path("items")));
    }

    @Override
    public Mono<List<Playlist>> getUserPlaylists(String userId) {
        // userId 形如 "ar:0025NhlN2yWrP4"（歌手）或 "pl:7011264340"（歌单）：
        // 歌手返回专辑列表，歌单返回其自身信息（后端只认 "此用户的歌单"，故两种都折成 Playlist）
        return getList(userId, 0, USER_PLAYLIST_LIMIT)
                .map(root -> {
                    String kind = text(root, "kind");
                    if ("playlist".equals(kind) || "album".equals(kind)) {
                        return List.of(new Playlist(
                                textOr(root, "id", userId),
                                stripTags(textOr(root, "name", "")),
                                textOr(root, "cover", null),
                                root.path("total").asInt(0),
                                PLATFORM));
                    }
                    List<Playlist> albums = new ArrayList<>();
                    for (JsonNode item : root.path("items")) {
                        albums.add(new Playlist(
                                textOr(item, "id", ""),
                                stripTags(textOr(item, "title", "")),
                                textOr(item, "cover", null),
                                item.path("trackCount").asInt(0),
                                PLATFORM));
                    }
                    return albums;
                });
    }

    @Override
    public Mono<List<UserSearchResult>> searchUsers(String keyword) {
        return getJson("/mp/user/search", Map.of("term", keyword, "limit", String.valueOf(USER_SEARCH_LIMIT)))
                .map(root -> {
                    List<UserSearchResult> users = new ArrayList<>();
                    for (JsonNode item : root.path("results")) {
                        users.add(new UserSearchResult(
                                textOr(item, "id", ""),
                                stripTags(textOr(item, "title", textOr(item, "artist", ""))),
                                textOr(item, "cover", null),
                                PLATFORM));
                    }
                    return users;
                });
    }

    @Override
    public Mono<PlayableMusic> getPlayableMusic(String musicId) {
        // 服务端只负责元数据；音频由浏览器直连 <publicBaseUrl>/mp/audio/{mid}
        return getJson("/mp/song/" + urlEncode(musicId), Map.of())
                .map(root -> {
                    JsonNode song = root.path("song");
                    return new PlayableMusic(
                            textOr(song, "id", musicId),
                            stripTags(textOr(song, "title", "")),
                            artistsOf(song),
                            song.path("durationMs").asLong(0),
                            PLATFORM,
                            audioUrl(musicId),
                            textOr(song, "cover", null));
                });
    }

    @Override
    public Mono<String> getLyric(String musicId) {
        // 歌词拿不到不应影响播放，全部降级为空字符串
        return getText("/mp/lyric/" + urlEncode(musicId))
                .onErrorResume(e -> {
                    log.warn("获取歌词失败 mid={}: {}", musicId, e.getMessage());
                    return Mono.just("");
                });
    }

    @Override
    public void prefetchMusic(String musicId) {
        if (!StringUtils.hasText(musicId)) {
            return;
        }
        // 入队即预热：让服务端在轮到该曲前把 FLAC 下载落盘（失败仅记日志，播放时仍会再试）
        post("/mp/prepare/" + urlEncode(musicId))
                .timeout(Duration.ofSeconds(10))
                .subscribe(
                        ignored -> log.debug("已请求预热缓存 mid={}", musicId),
                        e -> log.warn("预热请求失败 mid={}: {}", musicId, e.getMessage()));
    }

    // ------------------------------------------------------------------ 内部实现

    private Mono<JsonNode> getList(String listId, int offset, int limit) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("id", listId);
        params.put("offset", String.valueOf(Math.max(offset, 0)));
        params.put("limit", String.valueOf(limit <= 0 ? USER_PLAYLIST_LIMIT : limit));
        return getJson("/mp/list", params);
    }

    private Mono<JsonNode> getJson(String path, Map<String, String> params) {
        return request(webClient.get().uri(uri(path, params)), JsonNode.class);
    }

    private Mono<String> getText(String path) {
        return request(webClient.get().uri(uri(path, Map.of())), String.class);
    }

    private Mono<JsonNode> post(String path) {
        return request(webClient.post().uri(uri(path, Map.of())), JsonNode.class);
    }

    private <T> Mono<T> request(WebClient.RequestHeadersSpec<?> spec, Class<T> type) {
        ensureEnabled();
        return spec.header("X-MP-Token", cfg().getToken() == null ? "" : cfg().getToken())
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> errorFrom(response.statusCode(), body)))
                .bodyToMono(type)
                .timeout(Duration.ofSeconds(Math.max(cfg().getTimeoutSeconds(), 1)))
                .onErrorResume(e -> classify(e));
    }

    private void ensureEnabled() {
        if (!cfg().isEnabled()) {
            throw new ApiRequestException("自建音源已禁用（MP_ENABLED=false）");
        }
    }

    private URI uri(String path, Map<String, String> params) {
        String base = cfg().getBaseUrl();
        if (!StringUtils.hasText(base)) {
            throw new ApiRequestException("自建音源未配置，请联系管理员设置 MP_API_URL");
        }
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(trimTrailingSlash(base)).path(path);
        params.forEach(builder::queryParam);
        return builder.build().encode(StandardCharsets.UTF_8).toUri();
    }

    /** 把服务端的 {@code {"error": "...", "message": "..."}} 直接透出给前端。 */
    private Mono<? extends Throwable> errorFrom(HttpStatusCode status, String body) {
        String message = null;
        if (StringUtils.hasText(body)) {
            try {
                JsonNode node = objectMapper.readTree(body);
                message = text(node, "message");
                if (message == null) {
                    message = text(node, "error");
                }
            } catch (Exception ignored) {
                // 非 JSON 响应，走下面的兜底文案
            }
        }
        if (message == null) {
            message = "自建音源服务返回 HTTP " + status.value();
        }
        log.error("自建音源请求失败: status={} body={}", status.value(), abbreviate(body));
        return Mono.error(new ApiRequestException(message));
    }

    /** 网络层/超时异常统一转成友好文案，避免 500 直接漏给前端。 */
    private <T> Mono<T> classify(Throwable e) {
        if (e instanceof ApiRequestException) {
            return Mono.error(e);
        }
        if (e instanceof TimeoutException) {
            log.error("自建音源请求超时: {}", e.getMessage());
            return Mono.error(new ApiRequestException("自建音源响应超时，请稍后重试"));
        }
        if (e instanceof WebClientRequestException || e instanceof java.net.UnknownHostException) {
            log.error("无法连接自建音源: {}", e.getMessage());
            return Mono.error(new ApiRequestException("无法连接自建音源服务，请检查 MP_API_URL 与网络"));
        }
        if (e instanceof WebClientResponseException wcre) {
            log.error("自建音源 HTTP 错误: {}", wcre.getMessage());
            return Mono.error(new ApiRequestException("自建音源服务异常（HTTP " + wcre.getStatusCode().value() + "）"));
        }
        return Mono.error(e);
    }

    private List<Music> mapMusics(JsonNode array) {
        List<Music> musics = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return musics;
        }
        for (JsonNode item : array) {
            if (!"song".equals(textOr(item, "type", "song"))) {
                continue;
            }
            musics.add(new Music(
                    textOr(item, "id", ""),
                    stripTags(textOr(item, "title", "")),
                    artistsOf(item),
                    item.path("durationMs").asLong(0),
                    PLATFORM,
                    textOr(item, "cover", null)));
        }
        return musics;
    }

    private List<String> artistsOf(JsonNode node) {
        List<String> artists = new ArrayList<>();
        JsonNode array = node.path("artists");
        if (array.isArray()) {
            for (JsonNode artist : array) {
                String name = artist.asText("");
                if (StringUtils.hasText(name)) {
                    artists.add(stripTags(name));
                }
            }
        }
        if (artists.isEmpty()) {
            String single = text(node, "artist");
            if (StringUtils.hasText(single)) {
                artists.add(stripTags(single));
            }
        }
        return artists;
    }

    /** 搜索结果里命中词被 QQ 用 {@code <em>} 包住，展示前剥掉标签。 */
    static String stripTags(String value) {
        if (value == null || value.indexOf('<') < 0) {
            return value;
        }
        return value.replaceAll("<[^>]*>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "…";
    }
}
