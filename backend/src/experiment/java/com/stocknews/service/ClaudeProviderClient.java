package com.stocknews.service;

import java.util.List;
import java.util.Map;

/**
 * Claude: 운영과 같은 호출 코드(ClaudeAnalysisClient, 공식 Anthropic Java SDK)를 그대로 쓴다.
 * 운영과 실험이 같은 요청을 보내므로 따로 맞출 필요가 없다. 키는 환경변수 ANTHROPIC_API_KEY.
 *
 * 이 모델 규칙: temperature·top_p·top_k 를 보내지 않는다. thinking 은 기본(adaptive)으로 두고 생략한다.
 * budget_tokens·assistant prefill 을 쓰지 않는다. max_tokens 16000, 비스트리밍. (상세는 ClaudeAnalysisClient)
 */
final class ClaudeProviderClient implements ProviderClient {

    private final String apiKey = System.getenv("ANTHROPIC_API_KEY");
    private ClaudeAnalysisClient client;

    @Override public String provider() { return "claude"; }

    @Override public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    private ClaudeAnalysisClient client() {
        if (client == null) client = new ClaudeAnalysisClient(apiKey);
        return client;
    }

    @Override
    public ProviderResult call(String modelId, String prompt, Map<String, Object> schema, FormatLevel level) {
        // 스키마는 운영 호출 코드가 운영 responseSchema() 로 만든다 (인자로 받은 schema 는 쓰지 않는다).
        try {
            GeminiAnalysisService.ModelResponse r = client().call(modelId, prompt);
            return new ProviderResult(r.text(), r.promptTokens(), r.outputTokens(), null, null);
        } catch (ClaudeAnalysisClient.CallException e) {
            return new ProviderResult(null, e.inputTokens(), e.outputTokens(), null, e.code());
        }
    }

    /** 구조화 출력(output_config.format) 한 수준만 쓴다. 내려가지 않는다 (기준선을 흐리지 않기 위해). */
    @Override
    public List<FormatLevel> formatLadder(String modelId) {
        return List.of(FormatLevel.SCHEMA);
    }

    @Override
    public List<String> requestSettings(String modelId) {
        return List.of(
                "형식 강제: SCHEMA (output_config.format). strict 플래그는 없고 내려가기 없음",
                "temperature·top_p·top_k 미전송 (이 모델은 기본값이 아닌 값을 400 으로 거부)",
                "구조화 출력: output_config.format (JSON 스키마). 운영 스키마에 object 마다 additionalProperties=false 를 더함",
                "thinking: 파라미터 생략(기본 adaptive). max_tokens " + ClaudeAnalysisClient.MAX_TOKENS + ", 비스트리밍",
                "재시도·타임아웃: SDK 기본 재시도, 호출 제한 " + ClaudeAnalysisClient.TIMEOUT.toSeconds() + "초 (운영과 같은 코드)",
                "출력 토큰 = output_tokens (생각 토큰 포함, 따로 보고되지 않음)");
    }
}
