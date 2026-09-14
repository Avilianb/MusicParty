package org.thornex.musicparty.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.EnqueueRequest;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.PlayableMusic;
import org.thornex.musicparty.dto.User;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.event.PlayerStateEvent;
import org.thornex.musicparty.event.SystemMessageEvent;
import org.thornex.musicparty.service.api.IMusicApiService;
import org.thornex.musicparty.service.api.MpMusicApiService;
import org.thornex.musicparty.service.stream.LiveStreamService;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MusicPlayerServiceStateTest {

    private final AppProperties props = new AppProperties();

    private MusicPlayerService build(ApplicationEventPublisher publisher, UserService userService,
                                     MusicQueueManager queueManager, List<IMusicApiService> apiServices) {
        return new MusicPlayerService(
                apiServices,
                userService,
                mock(LiveStreamService.class),
                queueManager,
                publisher,
                props);
    }

    private MusicPlayerService build(ApplicationEventPublisher publisher) {
        return build(publisher, mock(UserService.class), mock(MusicQueueManager.class), List.of());
    }

    @Test
    void syncHeartbeatPublishesPlayerStateWhileNotIdle() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MusicPlayerService service = build(publisher);

        clearInvocations(publisher);
        service.broadcastSyncHeartbeat();

        verify(publisher, times(1)).publishEvent(any(PlayerStateEvent.class));
    }

    @Test
    void syncHeartbeatSkipsWhenIdleAndPaused() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        MusicPlayerService service = build(publisher);

        service.setPausedForTest(true);
        clearInvocations(publisher);

        service.broadcastSyncHeartbeat();

        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void enqueueRejectsForeignPlatformAndDisabledOwnSource() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UserService userService = mock(UserService.class);
        when(userService.getUser("sess")).thenReturn(Optional.of(new User("tok1", "sess", "小明")));
        MusicQueueManager queueManager = mock(MusicQueueManager.class);
        IMusicApiService api = mock(IMusicApiService.class);
        when(api.getPlatformName()).thenReturn(MpMusicApiService.PLATFORM);
        props.getMp().setEnabled(false);

        MusicPlayerService service = build(publisher, userService, queueManager, List.of(api));

        service.enqueue(new EnqueueRequest("netease", "1x"), "sess");
        service.enqueue(new EnqueueRequest(MpMusicApiService.PLATFORM, "1x"), "sess");

        ArgumentCaptor<SystemMessageEvent> captor = ArgumentCaptor.forClass(SystemMessageEvent.class);
        verify(publisher, times(2)).publishEvent(captor.capture());
        List<String> messages = captor.getAllValues().stream().map(SystemMessageEvent::getPayload).toList();
        assertTrue(messages.stream().anyMatch(m -> m.contains("不支持的音源 netease")),
                "非自建音源必须被拒绝，实际: " + messages);
        assertTrue(messages.stream().anyMatch(m -> m.contains("自建音源已被禁用")),
                "mp.enabled=false 时必须拒绝入队，实际: " + messages);

        verify(api, never()).prefetchMusic(any());
        verify(api, never()).getPlayableMusic(any());
        verifyNoInteractions(queueManager);
    }

    @Test
    void enqueuePrefetchesAndStoresReadyItemForOwnSource() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        UserService userService = mock(UserService.class);
        when(userService.getUser("sess")).thenReturn(Optional.of(new User("tok1", "sess", "小明")));
        MusicQueueManager queueManager = mock(MusicQueueManager.class);
        when(queueManager.getQueueSnapshot()).thenReturn(List.of());

        IMusicApiService api = mock(IMusicApiService.class);
        when(api.getPlatformName()).thenReturn(MpMusicApiService.PLATFORM);
        when(api.getPlayableMusic("0039MnYb0qxYhV")).thenReturn(Mono.just(new PlayableMusic(
                "0039MnYb0qxYhV", "晴天", List.of("周杰伦"), 269_000L, MpMusicApiService.PLATFORM,
                "http://mp/mp/audio/0039MnYb0qxYhV?token=t", "http://mp/c.jpg")));
        when(queueManager.add(any(), any(), any())).thenAnswer(invocation -> new MusicQueueItem(
                "q1", invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));

        MusicPlayerService service = build(publisher, userService, queueManager, List.of(api));

        service.enqueue(new EnqueueRequest(MpMusicApiService.PLATFORM, "0039MnYb0qxYhV"), "sess");

        // 入队即预热（服务端提前落盘），且队列项必须以 READY + 自建音源的元数据落库
        verify(api).prefetchMusic("0039MnYb0qxYhV");
        verify(queueManager).add(
                argThat(m -> "0039MnYb0qxYhV".equals(m.id())
                        && "晴天".equals(m.name())
                        && List.of("周杰伦").equals(m.artists())
                        && 269_000L == m.duration()
                        && MpMusicApiService.PLATFORM.equals(m.platform())),
                argThat(u -> "tok1".equals(u.token())),
                eq(QueueItemStatus.READY));
        verify(publisher).publishEvent(any(SystemMessageEvent.class));
    }
}
