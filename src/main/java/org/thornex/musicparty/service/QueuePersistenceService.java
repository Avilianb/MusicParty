package org.thornex.musicparty.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.SettingsSnapshot;
import org.thornex.musicparty.service.api.MpMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.io.File;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueuePersistenceService {

    private final MusicQueueManager musicQueueManager;
    private final ChatService chatService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final MusicPlayerService musicPlayerService;
    private final AuthController authController;
    private final LiveStreamService liveStreamService;

    @PostConstruct
    public void init() {
        loadData();
    }

    @PreDestroy
    public void cleanup() {
        saveData();
    }

    @Scheduled(fixedDelayString = "${app.music-api.queue.persistence-interval-ms:60000}")
    public void scheduledSave() {
        saveData();
    }

    synchronized void saveData() {
        try {
            File file = getPersistenceFile();
            PersistentData data = new PersistentData();
            data.setQueue(musicQueueManager.getQueueSnapshot());
            data.setHistory(musicQueueManager.getHistorySnapshot());
            data.setChatHistory(chatService.getHistoryFull());
            data.setSettings(buildSettingsSnapshot());

            objectMapper.writeValue(file, data);
            log.debug("Queue, music history and chat history saved to {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to save persistence data", e);
        }
    }

    synchronized void loadData() {
        File file = getPersistenceFile();
        if (!file.exists()) {
            log.info("No persistence file found at {}, starting fresh.", file.getAbsolutePath());
            return;
        }

        try {
            // 先按树读取再做兼容迁移：旧文件里的 platform=netease/bilibili、
            // status=PENDING/DOWNLOADING/PLAYING 都是已经不存在的取值，直接反序列化会抛错，
            // 导致整份 queue-data.json（含聊天记录/设置）被静默丢弃。
            JsonNode root = objectMapper.readTree(file);
            int dropped = sanitize(root);
            PersistentData data = objectMapper.treeToValue(root, PersistentData.class);

            musicQueueManager.restore(
                data.getQueue() != null ? data.getQueue() : Collections.emptyList(),
                data.getHistory() != null ? data.getHistory() : Collections.emptyList()
            );

            chatService.restore(data.getChatHistory() != null ? data.getChatHistory() : Collections.emptyList());

            applySettings(data.getSettings());

            log.info("Restored {} queue items, {} music history items and {} chat messages from {} (dropped {} legacy entries)",
                data.getQueue() != null ? data.getQueue().size() : 0,
                data.getHistory() != null ? data.getHistory().size() : 0,
                data.getChatHistory() != null ? data.getChatHistory().size() : 0,
                file.getAbsolutePath(), dropped);
        } catch (Exception e) {
            log.error("Failed to load persistence data from {}", file.getAbsolutePath(), e);
        }
    }

    /**
     * 旧数据迁移：丢弃非自建音源（platform != qq）的队列/历史项，把遗留的
     * PENDING/DOWNLOADING/PLAYING 归一化为 READY。返回丢弃条数。
     */
    private int sanitize(JsonNode root) {
        int dropped = 0;
        dropped += filterByPlatform(root.get("queue"), true);
        dropped += filterByPlatform(root.get("history"), false);
        return dropped;
    }

    private int filterByPlatform(JsonNode array, boolean normalizeStatus) {
        if (!(array instanceof ArrayNode items)) {
            return 0;
        }
        int dropped = 0;
        for (int i = items.size() - 1; i >= 0; i--) {
            JsonNode item = items.get(i);
            // 队列项是 {"music":{...}} 包装结构，历史项是裸 Music —— 两处字段层级不同
            JsonNode music = item.has("music") ? item.path("music") : item;
            String platform = music.path("platform").asText(null);
            if (!MpMusicApiService.PLATFORM.equals(platform)) {
                items.remove(i);
                dropped++;
                continue;
            }
            if (normalizeStatus && item instanceof ObjectNode object) {
                String status = item.path("status").asText("");
                if (!"READY".equals(status) && !"FAILED".equals(status)) {
                    object.put("status", "READY");
                }
            }
        }
        return dropped;
    }

    private void applySettings(SettingsSnapshot s) {
        if (s == null) {
            log.info("No settings section in persistence file, skipping settings restore.");
            return;
        }

        if (s.player() != null) {
            musicPlayerService.applyPlayerSettings(s.player());
        }

        if (s.systemConfig() != null) {
            SettingsSnapshot.SystemConfigSettings cfg = s.systemConfig();
            if (cfg.maxQueueSize() != null) appProperties.getQueue().setMaxSize(cfg.maxQueueSize());
            if (cfg.maxHistorySize() != null) appProperties.getQueue().setHistorySize(cfg.maxHistorySize());
            if (cfg.maxUserSongs() != null) appProperties.getQueue().setMaxUserSongs(cfg.maxUserSongs());
            if (cfg.maxPlaylistImportSize() != null) appProperties.getPlayer().setMaxPlaylistImportSize(cfg.maxPlaylistImportSize());
            if (cfg.maxChatHistorySize() != null) appProperties.getChat().setMaxHistorySize(cfg.maxChatHistorySize());
            if (cfg.minChatIntervalMs() != null) appProperties.getChat().setMinIntervalMs(cfg.minChatIntervalMs());
            if (cfg.maxChatMessageLength() != null) appProperties.getChat().setMaxMessageLength(cfg.maxChatMessageLength());
            if (cfg.mpEnabled() != null) appProperties.getMp().setEnabled(cfg.mpEnabled());
        }

        if (s.roomPassword() != null) authController.forceSetPassword(s.roomPassword());
        if (s.streamEnabled() != null) liveStreamService.setEnabled(s.streamEnabled());

        log.info("Restored persisted runtime settings from {}", appProperties.getQueue().getPersistenceFile());
    }

    private SettingsSnapshot buildSettingsSnapshot() {
        return new SettingsSnapshot(
                musicPlayerService.getPlayerSettings(),
                authController.getRawPassword(),
                liveStreamService.isEnabled(),
                new SettingsSnapshot.SystemConfigSettings(
                        appProperties.getQueue().getMaxSize(),
                        appProperties.getQueue().getHistorySize(),
                        appProperties.getQueue().getMaxUserSongs(),
                        appProperties.getPlayer().getMaxPlaylistImportSize(),
                        appProperties.getChat().getMaxHistorySize(),
                        appProperties.getChat().getMinIntervalMs(),
                        appProperties.getMp().isEnabled(),
                        appProperties.getChat().getMaxMessageLength()));
    }

    private File getPersistenceFile() {
        String path = appProperties.getQueue().getPersistenceFile();
        File file = new File(path);
        if (file.getParentFile() != null && !file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    @Data
    private static class PersistentData {
        private List<MusicQueueItem> queue;
        private List<Music> history;
        private List<org.thornex.musicparty.dto.ChatMessage> chatHistory;
        private SettingsSnapshot settings;
    }
}
