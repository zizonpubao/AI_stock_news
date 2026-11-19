package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 호출 (관리용 모델 비교 실험 전용, 운영 분석 체인은 쓰지 않는다).
 * 운영 의존성에 OpenAI SDK 를 더하지 않으려고 WebClient 로 직접 부른다. 재시도는 하지 않는다.
 *
 * 요청 모양은 모델 비교 실험(backend/src/experiment 의 OpenAiProviderClient, 공식 SDK)이 보내는 것과 같게 맞췄지만,
 * 이 클래스의 실제 키 호출은 확인하지 못했다 (요청 본문 모양과 응답 해석만 단위 테스트로 확인).
 * 키는 헤더로만 보내고 로그·예외에 남기지 않는다. 실패는 고정된 짧은 코드(CallException.code)로만 알린다.
 */
final class OpenAiAnalysisClient {

    static final String URL = "https://api.openai.com/v1/chat/completions";
    static final String SCHEMA_NAME = "stock_analysis";
    static final int MAX_COMPLETION_TOKENS = 16000;
    static final Duration TIMEOUT = Duration.ofSeconds(180);

    private final String apiKey;
    private final ObjectMapper objectMapper;

    OpenAiAnalysisClient(String apiKey, ObjectMapper objectMapper) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    /** 요청 본문. 샘플링 파라미터(temperature 등)는 보내지 않는다. */
    static Map<String, Object> buildBody(String model, String prompt) {
        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("name", SCHEMA_NAME);
        jsonSchema.put("strict", true);
        jsonSchema.put("schema", AnalysisSchemas.withNoAdditionalProperties(GeminiAnalysisService.responseSchema()));

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "json_schema");
        responseFormat.put("json_schema", jsonSchema);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("response_format", responseFormat);
        body.put("max_completion_tokens", MAX_COMPLETION_TOKENS);
        return body;
    }

    /** 호출. 실패하면 코드만 담은 CallException. */
    GeminiAnalysisService.ModelResponse call(String model, String prompt) {
        String response;
        try {
            WebClient client = WebClient.builder()
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                    .build();
            response = client.post().uri(URL).bodyValue(buildBody(model, prompt))
                    .retrieve().bodyToMono(String.class).block(TIMEOUT);
        } catch (Exception e) {
            // 예외 메시지에는 응답 본문이 섞일 수 있으므로 상태 코드·고정 코드만 쓴다
            throw new ClaudeAnalysisClient.CallException(ModelExperimentService.classifyCallError(e), null, null);
        }
        return parse(objectMapper, response);
    }

    /** 응답 해석. refusal·잘림(finish_reason=length)·빈 내용은 실패 코드로 던진다. */
    static GeminiAnalysisService.ModelResponse parse(ObjectMapper mapper, String json) {
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw new ClaudeAnalysisClient.CallException("INVALID_JSON", null, null);
        }
        if (root == null) throw new ClaudeAnalysisClient.CallException("INVALID_JSON", null, null);
        Integer in = intOrNull(root.path("usage"), "prompt_tokens");
        Integer out = intOrNull(root.path("usage"), "completion_tokens"); // 추론 토큰 포함
        JsonNode choice = root.path("choices").path(0);
        if (choice.isMissingNode()) throw new ClaudeAnalysisClient.CallException("EMPTY_RESPONSE", in, out);
        JsonNode message = choice.path("message");
        if (!message.path("refusal").asText("").isBlank()) {
            throw new ClaudeAnalysisClient.CallException("REFUSAL", in, out);
        }
        if ("length".equals(choice.path("finish_reason").asText(""))) {
            throw new ClaudeAnalysisClient.CallException("MAX_TOKENS", in, out);
        }
        String text = message.path("content").isTextual() ? message.path("content").asText() : "";
        if (text.isBlank()) throw new ClaudeAnalysisClient.CallException("EMPTY_RESPONSE", in, out);
        return new GeminiAnalysisService.ModelResponse(text, in, out, null);
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isNumber() ? v.intValue() : null;
    }
}
