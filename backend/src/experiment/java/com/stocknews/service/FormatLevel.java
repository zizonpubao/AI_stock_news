package com.stocknews.service;

/**
 * 호출마다 실제로 적용된 JSON 형식 강제 수준 (강한 순서). 실험 전용.
 * SCHEMA_STRICT: JSON 스키마 + strict / SCHEMA: JSON 스키마(strict 없음) / JSON_MODE: JSON 객체 모드(스키마 없음) /
 * PROMPT_ONLY: 형식 강제 없이 프롬프트의 지시만.
 */
enum FormatLevel {
    SCHEMA_STRICT, SCHEMA, JSON_MODE, PROMPT_ONLY;

    /** 스키마로 강제되지 않아 프롬프트 뒤에 스키마 요약을 덧붙이는 수준 */
    boolean appendsSchemaNote() {
        return this == JSON_MODE || this == PROMPT_ONLY;
    }

    /** 응답이 코드펜스·설명 문장으로 감싸져 올 수 있어 검증 전에 JSON 을 뽑아내는 수준 */
    boolean unwrapsResponse() {
        return this == JSON_MODE || this == PROMPT_ONLY;
    }

    /**
     * 이 오류 코드가 "요청 형식을 제공자가 거부했다"는 뜻인가 (400 계열: 400, 422).
     * 인증(401/403)·404·429·5xx·타임아웃·연결 오류는 형식 문제가 아니므로 false.
     * (Gemini 의 잘못된 키 400 은 호출 측이 HTTP_400_AUTH 로 따로 분류해 여기서 false 가 된다.)
     */
    static boolean isFormatRejection(String errorCode) {
        return "HTTP_400".equals(errorCode) || "HTTP_422".equals(errorCode);
    }
}
