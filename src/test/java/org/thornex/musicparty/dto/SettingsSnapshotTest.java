package org.thornex.musicparty.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsSnapshotTest {

    // 生产 ObjectMapper 关闭 FAIL_ON_UNKNOWN_PROPERTIES，测试显式对齐
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void roundTripPreservesAllFields() throws Exception {
        SettingsSnapshot original = new SettingsSnapshot(
                new SettingsSnapshot.PlayerSettings(
                        "SHUFFLE", true, true, true, 0.75, 20, true, true, true),
                "room123", true,
                new SettingsSnapshot.SystemConfigSettings(
                        500, 100, 50, 200, 5000, 500L, true, 300));

        String json = mapper.writeValueAsString(original);
        SettingsSnapshot restored = mapper.readValue(json, SettingsSnapshot.class);

        assertEquals("SHUFFLE", restored.player().playMode());
        assertTrue(restored.player().fairShuffle());
        assertTrue(restored.player().allowOfflineShuffle());
        assertTrue(restored.player().voteSkipEnabled());
        assertEquals(0.75, restored.player().voteSkipThreshold());
        assertEquals(20, restored.player().voteSkipWaitTime());
        assertTrue(restored.player().pauseLocked());
        assertTrue(restored.player().skipLocked());
        assertTrue(restored.player().playModeLocked());
        assertEquals("room123", restored.roomPassword());
        assertTrue(restored.streamEnabled());
        assertEquals(500, restored.systemConfig().maxQueueSize());
        assertEquals(100, restored.systemConfig().maxHistorySize());
        assertEquals(50, restored.systemConfig().maxUserSongs());
        assertEquals(200, restored.systemConfig().maxPlaylistImportSize());
        assertEquals(5000, restored.systemConfig().maxChatHistorySize());
        assertEquals(500L, restored.systemConfig().minChatIntervalMs());
        assertTrue(restored.systemConfig().mpEnabled());
        assertEquals(300, restored.systemConfig().maxChatMessageLength());
    }

    @Test
    void nullSectionsRoundTripAsNull() throws Exception {
        SettingsSnapshot original = new SettingsSnapshot(
                new SettingsSnapshot.PlayerSettings(
                        "SEQUENTIAL", null, null, null, null, null, null, null, null),
                null, null, null);

        String json = mapper.writeValueAsString(original);
        SettingsSnapshot restored = mapper.readValue(json, SettingsSnapshot.class);

        assertNull(restored.roomPassword());
        assertNull(restored.systemConfig());
        assertNull(restored.player().voteSkipEnabled());
    }

    @Test
    void unknownJsonFieldsAreIgnored() throws Exception {
        // 模拟旧版读新版文件 / 未来字段：未知字段必须被忽略
        String json = "{\"player\":{\"playMode\":\"SHUFFLE\"},\"futureField\":123}";
        SettingsSnapshot restored = mapper.readValue(json, SettingsSnapshot.class);
        assertEquals("SHUFFLE", restored.player().playMode());
        assertNull(restored.player().voteSkipEnabled());
    }

    @Test
    void legacySourceFieldsAreIgnoredOnLoad() throws Exception {
        // 已删音源的持久化字段（privateDj / neteaseEnabled / bilibiliEnabled）留在旧文件里时，
        // 必须被忽略而不是让整份 settings 反序列化失败（否则队列/聊天记录一并丢失）。
        String json = "{"
                + "\"player\":{\"playMode\":\"REPEAT_ONE\"},"
                + "\"privateDj\":{\"mode\":\"DJ\",\"fillBlankEnabled\":true,\"joinQueueEnabled\":true,\"custodyEnabled\":true},"
                + "\"roomPassword\":\"room123\","
                + "\"systemConfig\":{\"maxQueueSize\":500,\"neteaseEnabled\":true,\"bilibiliEnabled\":true,"
                + "\"bilibiliMaxDurationMinutes\":15,\"mpEnabled\":false}}";

        SettingsSnapshot restored = mapper.readValue(json, SettingsSnapshot.class);

        assertEquals("REPEAT_ONE", restored.player().playMode());
        assertEquals("room123", restored.roomPassword());
        assertEquals(500, restored.systemConfig().maxQueueSize());
        assertFalse(restored.systemConfig().mpEnabled());
        assertNull(restored.systemConfig().maxChatMessageLength());
    }
}
