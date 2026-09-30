package com.tianchen.kb.web.dto;

/** 统一错误响应体，便于调用方与网关处理。 */
public record ErrorResponse(String error, String message, int status) {
}
