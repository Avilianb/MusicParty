package org.thornex.musicparty.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.controller.AuthController;
import org.thornex.musicparty.dto.ChatMessage;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.service.stream.LiveStreamService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * queue-data.json 的旧格式迁移：多音源时代落盘的数据（platform=netease/bilibili）、
 * 已删除的状态（PLAYING/PENDING/DOWNLOADING）以及已删除的 settings 段（privateDj 等）
 * 都必须在加载时被容忍——否则整份文件（含聊天记录与系统设置）会被静默丢弃。
 */
class QueuePersistenceServiceLegacyMigrationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @TempDir
    Path tempDir;

    private AppProperties propsWithFile() {
        AppProperties props = new AppProperties();
        props.getQueue().setPersistenceFile(tempDir.resolve("queue-data.json").toString());
        return props;
    }

    private QueuePersistenceService build(AppProperties props, MusicQueueManager qm, ChatService chat,
            MusicPlayerService player, AuthController auth, LiveStreamService stream) {
        return new QueuePersistenceService(qm, chat, props, mapper, player, auth, stream);
    }

    private void write(AppProperties props, String json) throws Exception {
        Files.writeString(Path.of(props.getQueue().getPersistenceFile()), json);
    }

    @SuppressWarnings("unchecked")
    @Test
    void legacyFileDropsForeignSourcesNormalizesStatusAndKeepsRest() throws Exception {
        AppProperties props = propsWithFile();
        props.getMp().setEnabled(true);
        write(props, """
                {
                  "queue": [
                    {"queueId":"q1","status":"PLAYING","music":{"id":"m1","name":"情歌","artists":["a"],"duration":1000,"platform":"qq","coverUrl":null},
                     "enqueuedBy":{"token":"t1","sessionId":"s1","name":"小明","isGuest":false}},
                    {"queueId":"q2","status":"READY","music":{"id":"n1","name":"网易云歌","artists":["b"],"duration":1000,"platform":"netease","coverUrl":null},
                     "enqueuedBy":{"token":"t1","sessionId":"s1","name":"小明","isGuest":false}},
                    {"queueId":"q3","status":"FAILED","music":{"id":"m3","name":"失败歌","artists":["c"],"duration":1000,"platform":"qq","coverUrl":null},
                     "enqueuedBy":{"token":"t2","sessionId":"s2","name":"小红","isGuest":false}},
                    {"queueId":"q4","status":"PENDING","music":{"id":"m4","name":"待下载","artists":["d"],"duration":1000,"platform":"qq","coverUrl":null},
                     "enqueuedBy":{"token":"t2","sessionId":"s2","name":"小红","isGuest":false}}
                  ],
                  "history": [
                    {"id":"h1","name":"b站历史","artists":["x"],"duration":1000,"platform":"bilibili","coverUrl":null},
                    {"id":"h2","name":"qq历史","artists":["y"],"duration":1000,"platform":"qq","coverUrl":null}
                  ],
                  "chatHistory": [
                    {"id":"c1","userId":"t1","userName":"小明","content":"点一首","timestamp":1700000000000,"type":"CHAT"},
                    {"id":"c2","userId":"SYSTEM","userName":"SYSTEM","content":"欢迎加入","timestamp":1700000000001,"type":"SYSTEM"}
                  ],
                  "settings": {
                    "player": {"playMode":"SHUFFLE","fairShuffle":false,"voteSkipEnabled":true},
                    "roomPassword": "room9",
                    "streamEnabled": true,
                    "privateDj": {"mode":"FM","fillBlankEnabled":true,"joinQueueEnabled":true,"custodyEnabled":false},
                    "systemConfig": {"maxQueueSize":321,"maxHistorySize":11,"maxChatHistorySize":77,
                                     "minChatIntervalMs":250,"maxChatMessageLength":140,
                                     "mpEnabled":false,"neteaseEnabled":true,"bilibiliEnabled":true,
                                     "bilibiliMaxDurationMinutes":15}
                  }
                }""");

        MusicQueueManager qm = mock(MusicQueueManager.class);
        ChatService chat = mock(ChatService.class);
        MusicPlayerService player = mock(MusicPlayerService.class);
        AuthController auth = mock(AuthController.class);
        LiveStreamService stream = mock(LiveStreamService.class);

        build(props, qm, chat, player, auth, stream).loadData();

        ArgumentCaptor<List<MusicQueueItem>> queueCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Music>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(qm).restore(queueCaptor.capture(), historyCaptor.capture());

        List<MusicQueueItem> restoredQueue = queueCaptor.getValue();
        assertEquals(List.of("m1", "m3", "m4"),
                restoredQueue.stream().map(i -> i.music().id()).collect(Collectors.toList()),
                "非 qq 项应被丢弃，其它项保持原顺序");
        assertEquals(List.of(QueueItemStatus.READY, QueueItemStatus.FAILED, QueueItemStatus.READY),
                restoredQueue.stream().map(MusicQueueItem::status).collect(Collectors.toList()),
                "PLAYING/PENDING 归一化为 READY，FAILED 必须保留");
        assertEquals("小明", restoredQueue.get(0).enqueuedBy().name(), "点歌人快照不应在迁移中丢失");

        List<Music> restoredHistory = historyCaptor.getValue();
        assertEquals(1, restoredHistory.size(), "历史里的 bilibili 项应被丢弃");
        assertEquals("h2", restoredHistory.get(0).id());

        ArgumentCaptor<List<ChatMessage>> chatCaptor = ArgumentCaptor.forClass(List.class);
        verify(chat).restore(chatCaptor.capture());
        assertEquals(2, chatCaptor.getValue().size(), "聊天记录不应因队列迁移而丢失");
        assertEquals("点一首", chatCaptor.getValue().get(0).content());

        // 设置段：mpEnabled 等新字段正常恢复（旧字段忽略）
        verify(player).applyPlayerSettings(argThat(p ->
                "SHUFFLE".equals(p.playMode()) && Boolean.FALSE.equals(p.fairShuffle())
                        && Boolean.TRUE.equals(p.voteSkipEnabled())));
        verify(auth).forceSetPassword("room9");
        verify(stream).setEnabled(true);
        assertFalse(props.getMp().isEnabled(), "mpEnabled=false 必须覆盖默认的 true");
        assertEquals(321, props.getQueue().getMaxSize());
        assertEquals(11, props.getQueue().getHistorySize());
        assertEquals(77, props.getChat().getMaxHistorySize());
        assertEquals(250L, props.getChat().getMinIntervalMs());
        assertEquals(140, props.getChat().getMaxMessageLength());
    }

    @SuppressWarnings("unchecked")
    @Test
    void fileWithOnlyForeignSourcesStillRestoresChatAndSettings() throws Exception {
        AppProperties props = propsWithFile();
        write(props, """
                {
                  "queue": [
                    {"queueId":"q1","status":"DOWNLOADING","music":{"id":"n1","name":"网易云","artists":["a"],"duration":1000,"platform":"netease","coverUrl":null},
                     "enqueuedBy":{"token":"t1","sessionId":"s1","name":"小明","isGuest":false}}
                  ],
                  "history": [
                    {"id":"h1","name":"b站","artists":["x"],"duration":1000,"platform":"bilibili","coverUrl":null}
                  ],
                  "chatHistory": [
                    {"id":"c1","userId":"t1","userName":"小明","content":"还在吗","timestamp":1700000000000,"type":"CHAT"}
                  ],
                  "settings": {"player": {"playMode":"REPEAT_ONE"}, "systemConfig": {"maxUserSongs":7}}
                }""");

        MusicQueueManager qm = mock(MusicQueueManager.class);
        ChatService chat = mock(ChatService.class);
        MusicPlayerService player = mock(MusicPlayerService.class);

        build(props, qm, chat, player, mock(AuthController.class), mock(LiveStreamService.class)).loadData();

        ArgumentCaptor<List<MusicQueueItem>> queueCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Music>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(qm).restore(queueCaptor.capture(), historyCaptor.capture());
        assertTrue(queueCaptor.getValue().isEmpty(), "全部为旧音源的队列应迁移为空队列");
        assertTrue(historyCaptor.getValue().isEmpty(), "全部为旧音源的历史应迁移为空历史");

        ArgumentCaptor<List<ChatMessage>> chatCaptor = ArgumentCaptor.forClass(List.class);
        verify(chat).restore(chatCaptor.capture());
        assertEquals(1, chatCaptor.getValue().size());
        assertEquals("还在吗", chatCaptor.getValue().get(0).content());

        verify(player).applyPlayerSettings(argThat(p -> "REPEAT_ONE".equals(p.playMode())));
        assertEquals(7, props.getQueue().getMaxUserSongs());
    }
}
