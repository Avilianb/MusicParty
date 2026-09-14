package org.thornex.musicparty.enums;

/**
 * 明确队列中单曲的状态。
 *
 * <p>自建音源（MP）由服务端按需落盘缓存，入队即可播，不再有“等待解析/下载”的中间态，
 * 因此仅保留 {@code READY} 与 {@code FAILED}。</p>
 */
public enum QueueItemStatus {
    READY,          // 就绪，随时可播
    FAILED          // 取歌/解析失败
}
