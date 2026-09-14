package org.thornex.musicparty.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.music-api")
@Data
public class AppProperties {
    private MpApiConfig mp = new MpApiConfig();
    private String adminPassword;
    private String baseUrl;
    private String authorName = "ThorNex";
    private String backWords = "THORNEX";
    private String ffmpegPath = "ffmpeg"; // 默认使用环境变量中的 ffmpeg

    // 新增配置项
    private QueueConfig queue = new QueueConfig();
    private PlayerConfig player = new PlayerConfig();
    private ChatConfig chat = new ChatConfig();
    private AuthConfig auth = new AuthConfig();
    private StreamConfig stream = new StreamConfig();

    @Data
    public static class QueueConfig {
        private int maxSize = 1000;
        private int historySize = 50;
        private int maxUserSongs = 100;
        private String persistenceFile = "data/queue-data.json";
        private long persistenceIntervalMs = 60000; // Default save every 1 minute
    }

    @Data
    public static class PlayerConfig {
        private int maxPlaylistImportSize = 100;
        private boolean voteSkipEnabled = false;
        private double voteSkipThreshold = 0.5;
        private int voteSkipWaitTime = 15;
        private long syncBroadcastIntervalMs = 5000; // 周期状态广播间隔（ms），修复移动端后台同步漂移
    }

    @Data
    public static class ChatConfig {
        private int maxHistorySize = 1000;
        private long minIntervalMs = 1000;
        private int maxMessageLength = 200;
    }

    @Data
    public static class AuthConfig {
        private boolean rateLimitEnabled = true;
        private int maxAttempts = 5;
        private int windowSeconds = 60;
        private int blockDurationSeconds = 300;
    }

    @Data
    public static class ApiConfig {
        private String baseUrl;
    }

    /**
     * 直播流（radio）模块配置
     */
    @Data
    public static class StreamConfig {
        /** 最大同时连接的收听者数量（按连接数，非唯一 IP） */
        private int maxClients = 100;
        /** 每个收听者的缓冲队列容量（块数），chunkSizeBytes * bufferChunks ≈ 缓冲时长 */
        private int bufferChunks = 32;
        /** ffmpeg 输出的分块大小（字节），默认 16KB */
        private int chunkSizeBytes = 16384;
        /** seek 判定阈值（毫秒）：与实时进度的漂移超过该值才重启转码 */
        private long seekThresholdMs = 3000;
        /** ResponseBodyEmitter 超时（毫秒），默认 24h。Tomcat 默认 async 超时仅 30s，必须显式设大 */
        private long emitterTimeoutMs = 24 * 60 * 60 * 1000L;
    }

    /**
     * 自建音源（MP = 自己服务器上的 QQ 音乐 API）配置。
     * baseUrl 供后端调用（元数据/预热/歌词），publicBaseUrl 用于拼给浏览器播放的绝对音频地址；
     * publicBaseUrl 为空时回退 baseUrl。
     */
    @EqualsAndHashCode(callSuper = true)
    @Data
    public static class MpApiConfig extends ApiConfig {
        private String publicBaseUrl;
        private String token;
        private boolean enabled = true;
        /** 单次后端调用超时（秒） */
        private int timeoutSeconds = 10;
    }
}
