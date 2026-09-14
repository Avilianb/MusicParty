package org.thornex.musicparty.dto;

import java.util.List;

public record PlayableMusic(
        String id,
        String name,
        List<String> artists,
        long duration,
        String platform,
        String url, // 可播放的音频直链（自建音源：<publicBaseUrl>/mp/audio/{mid}?token=...）
        String coverUrl
) {}
