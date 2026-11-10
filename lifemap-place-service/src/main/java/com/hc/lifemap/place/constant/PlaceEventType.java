package com.hc.lifemap.place.constant;

/**
 * {@code place_event.event_type} 的取值，存库取 {@code name()}（库里是 VARCHAR(32)）。
 *
 * <p>用枚举而不是散落的字符串常量：media 侧对账要按类型分支处理，拼错一个字母不会报错，
 * 只会变成一条永远没人消费的事件。</p>
 */
public enum PlaceEventType {

    /** 地点被软删。media 据此判断它的照片已无归属，交给对账任务清理文件（P6-T03）。 */
    PLACE_DELETED
}
