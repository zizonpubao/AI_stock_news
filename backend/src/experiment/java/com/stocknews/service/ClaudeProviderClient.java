package com.stocknews.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude: 공식 Anthropic Java SDK(com.anthropic:anthropic-java). raw HTTP 를 쓰지 않는다.
 *
 * 근거(claude-api 스킬 문서): java/claude-api/README.md 의 기본 요청·클라이언트 생성·오류 처리,
 * java/claude-api/tool-use.md 의 "Structured Output" 마지막 줄(수동 스키마 경로
 * OutputConfig.builder().format(JsonOutputFormat.builder().schema(...))), shared/error-codes.md 의 Java 예외 표.
 * 키는 ANTHROPIC_API_KEY (AnthropicOkHttpClient.fromEnv 가 읽는다).
 *
 * 이 모델 규칙: temperature·top_p·top_k 를 보내지 않는다(기본값 아닌 값은 400). thinking 은 기본(adaptive)으로 두고
 * 파라미터를 생략한다. budget_tokens·assistant prefill 을 쓰지 않는다. max_tokens 16000, 비스트리밍.
 */
final class ClaudeProviderClient implements ProviderClient {

    private static final long MAX_TOKENS = 16000L;

    private final boolean configured;
    private AnthropicClient client;

    ClaudeProviderClient() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        this.configured = key != null && !key.isBlank();
    }

    @Override public String provider() { return "claude"; }

    @Override public boolean isConfigured() { return configured; }

    private AnthropicClient client() {
        if (client == null) {
            client = AnthropicOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(180)).build();
        }
        return client;
    }

    @Override
    public ProviderResult call(String modelId, String prompt, Map<String, Object> schema) {
        try {
            Map<String, JsonValue> props = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ExperimentSchemas.withNoAdditionalProperties(schema).entrySet()) {
                props.put(e.getKey(), JsonValue.from(e.getValue()));
            }
            JsonOutputFormat format = JsonOutputFormat.builder()
                    .schema(JsonOutputFormat.Schema.builder().additionalProperties(props).build())
                    .build();
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(modelId)
                    .maxTokens(MAX_TOKENS)
                    .outputConfig(OutputConfig.builder().format(format).build())
                    .addUserMessage(prompt)
                    .build();

            Message response = client().messages().create(params);

            Integer in = toInt(response.usage().inputTokens());
            Integer out = toInt(response.usage().outputTokens());
            // 응답을 읽기 전에 stop_reason 을 본다. refusal·max_tokens 는 실패로 분류한다.
            StopReason stop = response.stopReason().orElse(null);
            if (StopReason.REFUSAL.equals(stop)) return new ProviderResult(null, in, out, null, "REFUSAL");
            if (StopReason.MAX_TOKENS.equals(stop)) return new ProviderResult(null, in, out, null, "MAX_TOKENS");

            StringBuilder sb = new StringBuilder();
            response.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
            if (sb.toString().isBlank()) return new ProviderResult(null, in, out, null, "EMPTY_RESPONSE");
            // 생각 토큰은 output_tokens 에 포함되고 따로 보고되지 않는다 (확인 못 함 → null)
            return new ProviderResult(sb.toString(), in, out, null, null);
        } catch (RateLimitException e) {
            return ProviderResult.failure("RATE_LIMIT");
        } catch (AnthropicServiceException e) {
            return ProviderResult.failure("HTTP_" + e.statusCode());
        } catch (AnthropicIoException e) {
            return ProviderResult.failure(ProviderClient.isTimeout(e) ? "TIMEOUT" : "CONNECTION_ERROR");
        } catch (AnthropicException e) {
            return ProviderResult.failure("CALL_FAILED");
        } catch (RuntimeException e) {
            return ProviderResult.failure(ProviderClient.isTimeout(e) ? "TIMEOUT" : "CALL_FAILED");
        }
    }

    @Override
    public List<String> requestSettings(String modelId) {
        return List.of(
                "temperature·top_p·top_k 미전송 (이 모델은 기본값이 아닌 값을 400 으로 거부)",
                "구조화 출력: output_config.format (JSON 스키마). 운영 스키마에 object 마다 additionalProperties=false 를 더함",
                "thinking: 파라미터 생략(기본 adaptive). max_tokens " + MAX_TOKENS + ", 비스트리밍",
                "재시도·타임아웃: SDK 기본 재시도, 호출 제한 180초",
                "출력 토큰 = output_tokens (생각 토큰 포함, 따로 보고되지 않음)");
    }

    private static Integer toInt(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
