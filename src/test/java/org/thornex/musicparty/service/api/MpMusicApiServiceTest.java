package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.exception.ApiRequestException;
import reactor.core.publisher.Mono;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自建音源（MP，platform=qq）的协议契约：出站请求、上游响应字段映射、
 * 浏览器播放直链拼接、上游错误的透出与歌词降级。
 *
 * <p>用 {@link ExchangeFunction} 完全接管 HTTP 层，断言的是"服务端 /mp/* 协议"这一协作契约。</p>
 */
class MpMusicApiServiceTest {

    private static final String BASE = "http://mp.internal:8321";
    private static final String MID = "0039MnYb0qxYhV";

    private final AtomicReference<ClientRequest> lastRequest = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<HttpStatus> responseStatus = new AtomicReference<>(HttpStatus.OK);
    private final AtomicReference<String> responseContentType = new AtomicReference<>(MediaType.APPLICATION_JSON_VALUE);

    private MpMusicApiService service(String baseUrl, String publicBaseUrl, String token) {
        ExchangeFunction exchange = request -> {
            lastRequest.set(request);
            return Mono.just(ClientResponse.create(responseStatus.get())
                    .header(HttpHeaders.CONTENT_TYPE, responseContentType.get())
                    .body(responseBody.get())
                    .build());
        };
        AppProperties props = new AppProperties();
        props.getMp().setBaseUrl(baseUrl);
        props.getMp().setPublicBaseUrl(publicBaseUrl);
        props.getMp().setToken(token);
        return new MpMusicApiService(
                WebClient.builder().exchangeFunction(exchange).build(), new ObjectMapper(), props);
    }

    private MpMusicApiService service() {
        return service(BASE, "http://music.example.com", "tk");
    }

    private static String queryParam(ClientRequest request, String key) {
        String raw = request.url().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Test
    void searchMapsUpstreamSongFieldsAndStripsEmphasisTags() {
        responseBody.set("""
                {"code":0,"results":[
                  {"id":"0039MnYb0qxYhV","type":"song","title":"<em>晴天</em> &amp; 雨",
                   "artists":["<em>周杰伦</em>","杨瑞代"],"durationMs":269000,"cover":"http://c/1.jpg"},
                  {"id":"ar:0025NhlN2yWrP4","type":"singer","title":"周杰伦","artists":[]}
                ]}""");

        List<Music> musics = service().searchMusic("晴天").block();

        assertEquals(1, musics.size(), "非 song 类型的结果不应混入点歌列表");
        Music music = musics.get(0);
        assertEquals(MID, music.id());
        assertEquals("晴天 & 雨", music.name(), "<em> 标签与 HTML 实体都应被剥离");
        assertEquals(List.of("周杰伦", "杨瑞代"), music.artists());
        assertEquals(269_000L, music.duration(), "durationMs 必须映射到 duration");
        assertEquals(MpMusicApiService.PLATFORM, music.platform());
        assertEquals("http://c/1.jpg", music.coverUrl());

        ClientRequest request = lastRequest.get();
        assertEquals("/mp/search", request.url().getPath());
        assertEquals("晴天", queryParam(request, "term"));
        assertEquals("song", queryParam(request, "entity"));
        assertEquals("30", queryParam(request, "limit"));
        assertEquals("tk", request.headers().getFirst("X-MP-Token"), "必须带上服务端访问令牌");
    }

    @Test
    void playableMusicUrlPrefersPublicBaseUrlWithToken() {
        responseBody.set("""
                {"song":{"id":"0039MnYb0qxYhV","title":"<em>晴天</em>","artists":["周杰伦"],
                 "durationMs":269000,"cover":"http://c/1.jpg"}}""");

        // publicBaseUrl 带尾斜杠：拼接结果不应出现双斜杠
        PlayableMusic playable = service(BASE, "http://music.example.com/", "tk").getPlayableMusic(MID).block();

        assertEquals(MID, playable.id());
        assertEquals("晴天", playable.name());
        assertEquals(List.of("周杰伦"), playable.artists());
        assertEquals(269_000L, playable.duration());
        assertEquals(MpMusicApiService.PLATFORM, playable.platform());
        assertEquals("http://music.example.com/mp/audio/" + MID + "?token=tk", playable.url());
        assertEquals("/mp/song/" + MID, lastRequest.get().url().getPath());
    }

    @Test
    void playableMusicUrlFallsBackToBaseUrlWhenPublicBaseUrlMissing() {
        responseBody.set("""
                {"song":{"id":"0039MnYb0qxYhV","title":"晴天","artists":["周杰伦"],"durationMs":269000}}""");

        PlayableMusic playable = service(BASE + "/", "", "").getPlayableMusic(MID).block();

        assertEquals(BASE + "/mp/audio/" + MID, playable.url(),
                "未配置 publicBaseUrl 时回退 baseUrl；未配置 token 时不带 token 参数");
        assertNull(playable.coverUrl());
    }

    @Test
    void upstreamCredentialErrorSurfacesUpstreamMessage() {
        responseStatus.set(HttpStatus.SERVICE_UNAVAILABLE);
        responseBody.set("{\"error\":\"credential\",\"message\":\"登录凭证已过期，请重新登录\"}");

        ApiRequestException ex = assertThrows(ApiRequestException.class,
                () -> service().getPlayableMusic(MID).block());

        assertEquals("登录凭证已过期，请重新登录", ex.getMessage(),
                "上游 error/message 必须原样透出，前端才能提示用户重新登录");
    }

    @Test
    void nonJsonUpstreamErrorFallsBackToStatusCodeMessage() {
        responseStatus.set(HttpStatus.BAD_GATEWAY);
        responseBody.set("<html>502 Bad Gateway</html>");

        ApiRequestException ex = assertThrows(ApiRequestException.class,
                () -> service().searchMusic("晴天").block());

        assertEquals("自建音源服务返回 HTTP 502", ex.getMessage());
    }

    @Test
    void lyricIsReturnedVerbatim() {
        responseContentType.set(MediaType.TEXT_PLAIN_VALUE);
        responseBody.set("[00:00.00] 从前从前\n[00:05.00] 有个人爱你很久");

        assertEquals("[00:00.00] 从前从前\n[00:05.00] 有个人爱你很久",
                service().getLyric(MID).block());
        assertEquals("/mp/lyric/" + MID, lastRequest.get().url().getPath());
    }

    @Test
    void lyricFailureDegradesToEmptyString() {
        responseStatus.set(HttpStatus.SERVICE_UNAVAILABLE);
        responseBody.set("{\"error\":\"credential\",\"message\":\"登录凭证已过期\"}");

        assertEquals("", service().getLyric(MID).block(), "歌词取不到不应影响播放，必须降级为空串");
    }
}
