package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gemini: 운영의 호출 코드(GeminiAnalysisService.buildRequestBody / callModel)를 그대로 쓴다.
 * 운영 클래스는 스프링 빈이라 키를 @Value 로 받으므로, 실험에서는 인스턴스를 직접 만들고 키 필드만 채운다.
 * (저장소는 쓰지 않으므로 null)
 */
final class GeminiProviderClient implements ProviderClient {

    private final String apiKey = System.getenv("GEMINI_API_KEY");
    private final GeminiAnalysisService service;

    GeminiProviderClient(ObjectMapper mapper) {
        this.service = new GeminiAnalysisService(null, mapper);
        if (isConfigured()) setField("geminiApiKey", apiKey);
    }

    /** 운영 프롬프트 생성기. 프롬프트 만들기에만 쓴다. */
    GeminiAnalysisService service() { return service; }

    @Override public String provider() { return "gemini"; }

    @Override public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    @Override
    public ProviderResult call(String modelId, String prompt, Map<String, Object> schema) {
        try {
            // 스키마는 buildRequestBody 가 운영 responseSchema() 로 넣는다 (인자로 받은 schema 는 쓰지 않는다).
            GeminiAnalysisService.ModelResponse r = service.callModel(modelId, service.buildRequestBody(modelId, prompt));
            Integer out = r.outputTokens();
            Integer thought = r.thoughtTokens();
            Integer total = (out == null && thought == null) ? null : (out == null ? 0 : out) + (thought == null ? 0 : thought);
            return new ProviderResult(r.text(), r.promptTokens(), total, thought, null);
        } catch (Exception e) {
            return ProviderResult.failure(ModelExperimentService.classifyCallError(e));
        }
    }

    @Override
    public List<String> requestSettings(String modelId) {
        List<String> s = new ArrayList<>();
        Map<String, Object> body = service.buildRequestBody(modelId, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) body.get("generationConfig");
        s.add(cfg.containsKey("temperature")
                ? "temperature " + cfg.get("temperature") + " 전송 (운영 buildRequestBody 규칙)"
                : "temperature 미전송 (운영 buildRequestBody 규칙: gemini-3·4 계열)");
        s.add("구조화 출력: generationConfig.responseJsonSchema (운영 스키마 그대로), maxOutputTokens " + cfg.get("maxOutputTokens"));
        s.add("호출: 운영 callModel — 5xx·429 는 최대 2회 재시도, 제한 90초");
        s.add("출력 토큰 = 응답 토큰 + 생각 토큰(thoughtsTokenCount) 합산");
        return s;
    }

    private void setField(String name, String value) {
        try {
            Field f = GeminiAnalysisService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("운영 Gemini 클래스의 필드 이름이 바뀌었습니다: " + name);
        }
    }
}
