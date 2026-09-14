package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.PlayMode;
import org.thornex.musicparty.enums.Priority;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 队列调度纯逻辑：置顶优先、公平随机轮询、离线过滤回退、状态过滤、去重与容量。
 * 这些行为不依赖任何音源实现，是自建音源改造后仍然生效的公开契约。
 */
class MusicQueueManagerTest {

    private final AppProperties props = new AppProperties();
    private final MusicQueueManager qm = new MusicQueueManager(props);

    private static Music music(String id) {
        return new Music(id, "song-" + id, List.of("artist"), 1000L, "qq", null);
    }

    private static UserSummary user(String token) {
        return new UserSummary(token, "sess-" + token, "user-" + token, false);
    }

    private static Map<String, QueueItemStatus> readyMap(String... ids) {
        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        for (String id : ids) {
            statusMap.put(id, QueueItemStatus.READY);
        }
        return statusMap;
    }

    @Test
    void globalTopIsSelectedBeforeRegularItems() {
        qm.add(music("1"), user("t1"), QueueItemStatus.READY);
        MusicQueueItem second = qm.add(music("2"), user("t1"), QueueItemStatus.READY);

        assertEquals(TopResult.GLOBAL, qm.top(second.queueId(), PlayMode.SEQUENTIAL));

        MusicQueueItem first = qm.pollNext(PlayMode.SEQUENTIAL, false, false, readyMap("1", "2"), Set.of("t1"));
        assertEquals("2", first.music().id(), "全局置顶必须优先出队");
        assertEquals(Priority.GLOBAL_TOP, first.priority());
        assertEquals("1", qm.pollNext(PlayMode.SEQUENTIAL, false, false, readyMap("1"), Set.of("t1")).music().id());
    }

    @Test
    void failedItemStaysSelectableForRetry() {
        qm.add(music("1"), user("t1"), QueueItemStatus.FAILED);

        Map<String, QueueItemStatus> statusMap = new HashMap<>();
        statusMap.put("1", QueueItemStatus.FAILED);

        MusicQueueItem picked = qm.pollNext(PlayMode.SEQUENTIAL, false, false, statusMap, Set.of("t1"));
        assertNotNull(picked, "FAILED 项应仍可出队重试，而不是被永久跳过");
        assertEquals("1", picked.music().id());
    }

    @Test
    void fairShuffleFallsBackToOfflineSongsWhenNoOnlineUserQueued() {
        qm.add(music("1"), user("offline-token"), QueueItemStatus.READY);

        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, true, false, readyMap("1"), Set.of("online-token"));
        assertNotNull(picked, "没有在线用户点歌时，公平随机应回退播放离线用户的歌");
        assertEquals("1", picked.music().id());
    }

    @Test
    void totalShuffleFallsBackToOfflineSongsWhenNoOnlineUserQueued() {
        qm.add(music("1"), user("offline-token"), QueueItemStatus.READY);

        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, false, false, readyMap("1"), Set.of("online-token"));
        assertNotNull(picked, "没有在线用户点歌时，普通随机应回退播放离线用户的歌");
        assertEquals("1", picked.music().id());
    }

    @Test
    void fairShuffleStillPrefersOnlineSongsWhenOnlineUserQueued() {
        qm.add(music("1"), user("offline-token"), QueueItemStatus.READY);
        qm.add(music("2"), user("online-token"), QueueItemStatus.READY);

        MusicQueueItem picked = qm.pollNext(PlayMode.SHUFFLE, true, false, readyMap("1", "2"), Set.of("online-token"));
        assertEquals("2", picked.music().id(), "在线用户有点歌时，公平随机仍应排除离线、优先在线用户的歌");
    }

    @Test
    void fairShuffleRoundRobinsBetweenUsers() {
        qm.add(music("a1"), user("a-token"), QueueItemStatus.READY);
        qm.add(music("a2"), user("a-token"), QueueItemStatus.READY);
        qm.add(music("b1"), user("b-token"), QueueItemStatus.READY);
        Set<String> online = Set.of("a-token", "b-token");

        MusicQueueItem first = qm.pollNext(PlayMode.SHUFFLE, true, false, readyMap("a1", "a2", "b1"), online);
        MusicQueueItem second = qm.pollNext(PlayMode.SHUFFLE, true, false, readyMap("a1", "a2", "b1"), online);

        assertEquals("a-token", first.enqueuedBy().token());
        assertEquals("b-token", second.enqueuedBy().token(),
                "严格轮询：A 点两首时，B 的第一首必须在 A 的第二首之前播出");
    }

    @Test
    void duplicateMusicAndOverflowAreRejected() {
        qm.add(music("1"), user("t1"), QueueItemStatus.READY);

        assertNull(qm.add(music("1"), user("t2"), QueueItemStatus.READY), "同一首歌不得重复入队");
        assertEquals(1, qm.getQueueSnapshot().size());

        props.getQueue().setMaxSize(1);
        assertNull(qm.add(music("2"), user("t1"), QueueItemStatus.READY), "队列已满时应拒绝入队");
        assertEquals(1, qm.getQueueSnapshot().size());
    }

    @Test
    void removeByUserDropsOnlyThatUsersSongs() {
        qm.add(music("1"), user("t1"), QueueItemStatus.READY);
        qm.add(music("2"), user("t2"), QueueItemStatus.READY);
        qm.add(music("3"), user("t1"), QueueItemStatus.READY);

        assertEquals(2, qm.removeByUser("t1"));

        List<MusicQueueItem> remaining = qm.getQueueSnapshot();
        assertEquals(1, remaining.size());
        assertEquals("2", remaining.get(0).music().id());
    }

    @Test
    void emptyQueueFallsBackToHistoryAsAutoDj() {
        assertNull(qm.pollNext(PlayMode.SEQUENTIAL, false, false, Collections.emptyMap(), Collections.emptySet()),
                "没有队列也没有历史时应返回 null");

        qm.addToHistory(music("h1"));

        MusicQueueItem autoDj = qm.pollNext(PlayMode.SEQUENTIAL, false, false, Collections.emptyMap(), Collections.emptySet());
        assertNotNull(autoDj, "队列空但有历史时应从历史补歌，而不是空转");
        assertEquals("h1", autoDj.music().id());
        assertEquals("SYSTEM", autoDj.enqueuedBy().token());
        assertEquals(QueueItemStatus.READY, autoDj.status());
    }
}
