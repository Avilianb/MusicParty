package org.thornex.musicparty.dto;

/**
 * queue-data.json 顶层 settings 的持久化快照。全部装箱类型，字段缺失时反序列化为 null。
 *
 * <p>旧的 privateDj / neteaseEnabled / bilibiliEnabled 等字段已随音源收敛删除；
 * 遗留 JSON 中的多余字段由 Jackson 忽略（{@code FAIL_ON_UNKNOWN_PROPERTIES} 关闭），
 * 但枚举值 old 状态（PENDING/DOWNLOADING/PLAYING）在加载时会归一化为 READY。</p>
 */
public record SettingsSnapshot(
        PlayerSettings player,          // 播放相关（MusicPlayerService 运行时状态）
        String roomPassword,            // null=未初始化(恢复跳过) / ""=无密码 / 其它=密码
        Boolean streamEnabled,          // 直播推流开关
        SystemConfigSettings systemConfig // 系统参数
) {
    public record PlayerSettings(
            String playMode,            // SEQUENTIAL / SHUFFLE / REPEAT_ONE
            Boolean fairShuffle,
            Boolean allowOfflineShuffle,
            Boolean voteSkipEnabled,
            Double voteSkipThreshold,
            Integer voteSkipWaitTime,
            Boolean pauseLocked,
            Boolean skipLocked,
            Boolean playModeLocked
    ) {}

    public record SystemConfigSettings(
            Integer maxQueueSize, Integer maxHistorySize, Integer maxUserSongs,
            Integer maxPlaylistImportSize, Integer maxChatHistorySize,
            Long minChatIntervalMs,
            Boolean mpEnabled,
            Integer maxChatMessageLength
    ) {}
}
