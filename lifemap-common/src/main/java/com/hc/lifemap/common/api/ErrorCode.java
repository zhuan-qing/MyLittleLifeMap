package com.hc.lifemap.common.api;

/**
 * 全服务统一错误码。
 *
 * <p>约定：code 为 0 表示成功；非 0 时前两位区分错误大类，后三位为业务序号。
 * 联调阶段（P6）以本枚举为唯一真源，禁止各服务私自定义字符串错误。</p>
 */
public enum ErrorCode {

    OK(0, "ok"),

    /* 1xxxx 客户端错误 */
    BAD_REQUEST(10400, "请求参数不合法"),
    UNAUTHORIZED(10401, "未登录或登录已过期"),
    NOT_FOUND(10404, "资源不存在"),
    PAYLOAD_TOO_LARGE(10413, "上传文件过大"),
    UNSUPPORTED_MEDIA_TYPE(10415, "不支持的文件类型"),

    /* 2xxxx 外部依赖错误 */
    GEO_PROVIDER_ERROR(20500, "高德接口调用失败"),
    STORAGE_ERROR(20501, "对象存储读写失败"),

    /* 5xxxx 服务端错误 */
    INTERNAL_ERROR(50000, "服务内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
