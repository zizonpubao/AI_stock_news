package com.stocknews.service;

import java.util.List;
import java.util.Map;

/** 모델 제공자 호출의 공통 인터페이스 (실험 전용). 구현은 키·응답 헤더를 어디에도 남기지 않는다. */
interface ProviderClient {

    /** 한 번 호출의 결과. errorCode 가 null 이고 text 가 있으면 성공. */
    record ProviderResult(String text, Integer inputTokens, Integer outputTokens, Integer reasoningTokens,
                          String errorCode) {
        static ProviderResult failure(String errorCode) {
            return new ProviderResult(null, null, null, null, errorCode);
        }
    }

    /** gemini / claude / openai */
    String provider();

    /** 키가 설정되어 있는가 (없으면 호출하지 않고 SKIPPED_NO_KEY 로 표시한다). */
    boolean isConfigured();

    /**
     * 모델 하나를 한 번 호출한다. 예외를 던지지 않고 errorCode 로 돌려준다.
     * outputTokens 는 비용 계산에 쓰는 총 출력 토큰(생각·추론 토큰 포함), reasoningTokens 는 그중 알 수 있는 생각 토큰.
     *
     * @param schema 운영 응답 스키마(JSON Schema, 소문자 타입). 구현이 제공자 요건에 맞게 변환한다.
     */
    ProviderResult call(String modelId, String prompt, Map<String, Object> schema);

    /** 결과 문서에 적을, 이 모델에만 해당하는 요청 설정 설명. */
    List<String> requestSettings(String modelId);

    /** 호출 예외 → 짧은 코드. 예외 메시지(본문·URL·헤더가 들어 있을 수 있음)는 쓰지 않는다. */
    static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
