package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini API (Gemini 와 Gemma 호스팅 모델 공용, 같은 GEMINI_API_KEY):
 * 운영의 호출 코드(GeminiAnalysisService.buildRequestBody / callModel)를 그대로 쓰되, 요청 본문은 복사본을 고쳐 보낸다.
 * 운영 클래스는 스프링 빈이라 키를 @Value 로 받으므로, 실험에서는 인스턴스를 직접 만들고 키 필드만 채운다.
 * (저장소는 쓰지 않으므로 null)
 *
 * 형식 강제 수준(운영 요청 = SCHEMA): SCHEMA = responseMimeType + responseJsonSchema,
 * JSON_MODE = responseMimeType 만, PROMPT_ONLY = 둘 다 없음. 400 이면 한 단계씩 내려간다.
 */
final class GeminiProviderClient implements ProviderClient {

    private static final List<FormatLevel> LADDER = List.of(FormatLevel.SCHEMA, FormatLevel.JSON_MODE, FormatLevel.PROMPT_ONLY);

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

    @Override public List<FormatLevel> formatLadder(String modelId) { return LADDER; }

    /** Gemma 호스팅 모델 (모델 ID 가 gemma- 로 시작) */
    static boolean isGemma(String modelId) {
        return modelId.startsWith("gemma-");
    }

    /**
     * 운영 buildRequestBody 가 만든 본문의 복사본을 수준·모델에 맞게 고친다 (순수 함수, 운영 코드는 건드리지 않는다).
     * - JSON_MODE: responseJsonSchema 제거 / PROMPT_ONLY: responseMimeType 도 제거
     * - Gemma: temperature 제거 (운영 규칙은 gemini-3·4 가 아닌 모델에 0.3 을 보내는데, Gemma 의 temperature 지원은 문서에서 확인 못 했다)
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> adjustBody(Map<String, Object> productionBody, String modelId, FormatLevel level) {
        Map<String, Object> body = new LinkedHashMap<>(productionBody);
        Map<String, Object> cfg = new LinkedHashMap<>((Map<String, Object>) productionBody.get("generationConfig"));
        if (level == FormatLevel.JSON_MODE || level == FormatLevel.PROMPT_ONLY) cfg.remove("responseJsonSchema");
        if (level == FormatLevel.PROMPT_ONLY) cfg.remove("responseMimeType");
        if (isGemma(modelId)) cfg.remove("temperature");
        body.put("generationConfig", cfg);
        return body;
    }

    @Override
    public ProviderResult call(String modelId, String prompt, Map<String, Object> schema, FormatLevel level) {
        try {
            // 스키마는 buildRequestBody 가 운영 responseSchema() 로 넣는다 (인자로 받은 schema 는 쓰지 않는다).
            Map<String, Object> body = adjustBody(service.buildRequestBody(modelId, prompt), modelId, level);
            GeminiAnalysisService.ModelResponse r = service.callModel(modelId, body);
            Integer out = r.outputTokens();
            Integer thought = r.thoughtTokens();
            Integer total = (out == null && thought == null) ? null : (out == null ? 0 : out) + (thought == null ? 0 : thought);
            return new ProviderResult(r.text(), r.promptTokens(), total, thought, null);
        } catch (Exception e) {
            printNotFoundDetail(modelId, e);
            return ProviderResult.failure(classify(e));
        }
    }

    /**
     * 404(모델 없음)일 때만 Google 오류 응답의 message 를 실행 로그에 남긴다.
     * 모델 목록에는 있는데 호출은 404 인 경우(2차 실험의 gemini-2.5-flash-lite)의 원인을 확인하기 위한 것이다.
     * API 키는 요청 헤더로만 보내므로 이 메시지에 들어 있지 않다. 다른 오류의 본문은 남기지 않는다.
     */
    private static void printNotFoundDetail(String modelId, Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException w && w.getStatusCode().value() == 404) {
                try {
                    String msg = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(w.getResponseBodyAsString()).path("error").path("message").asText("");
                    if (msg.length() > 240) msg = msg.substring(0, 240);
                    System.out.println("[gemini:" + modelId + "] 404 상세: " + msg);
                } catch (Exception ignore) {
                    // 본문을 해석하지 못하면 상세 없이 넘어간다
                }
                return;
            }
        }
    }

    /**
     * 오류 코드. Gemini 는 잘못된 API 키도 400 으로 돌려줄 수 있어, 응답 본문에 키 오류 표시가 있으면 HTTP_400_AUTH 로 따로 둔다
     * (형식 거부가 아니므로 내려가지 않게). 본문은 검사만 하고 어디에도 남기지 않는다.
     */
    static String classify(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException w && w.getStatusCode().value() == 400) {
                String b = w.getResponseBodyAsString();
                if (b != null && (b.contains("API_KEY_INVALID") || b.contains("API key not valid"))) return "HTTP_400_AUTH";
            }
        }
        return ModelExperimentService.classifyCallError(e);
    }

    @Override
    public List<String> requestSettings(String modelId) {
        List<String> s = new ArrayList<>();
        Map<String, Object> body = service.buildRequestBody(modelId, "");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) body.get("generationConfig");
        if (isGemma(modelId)) {
            s.add("temperature 미전송 (운영 규칙은 " + cfg.get("temperature") + " 을 보내지만, Gemma 의 temperature 지원은 문서에서 확인 못 해 실험 쪽 복사본에서 뺌)");
            s.add("thinking 관련 설정 미전송. Gemma 의 JSON 스키마 구조화 출력 지원 여부는 문서에서 확인 못 함 — 400 이면 아래 순서로 내려감");
        } else {
            s.add(cfg.containsKey("temperature")
                    ? "temperature " + cfg.get("temperature") + " 전송 (운영 buildRequestBody 규칙)"
                    : "temperature 미전송 (운영 buildRequestBody 규칙: gemini-3·4 계열)");
        }
        s.add("형식 강제 순서(처음 → 400 거부 때 한 단계씩 내려감): SCHEMA(generationConfig.responseMimeType + responseJsonSchema, 운영 스키마 그대로) → "
                + "JSON_MODE(responseMimeType application/json 만) → PROMPT_ONLY(둘 다 없음). maxOutputTokens " + cfg.get("maxOutputTokens"));
        s.add("잘못된 API 키로 오는 400 은 형식 거부로 보지 않고 HTTP_400_AUTH 로 기록해 내려가지 않음");
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
