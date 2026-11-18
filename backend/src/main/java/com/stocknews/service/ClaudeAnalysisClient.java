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
import java.util.Map;

/**
 * Claude 호출 (운영 + 모델 비교 실험 공용). 공식 Anthropic Java SDK(com.anthropic:anthropic-java)만 쓰고 raw HTTP 는 쓰지 않는다.
 *
 * 호출 방식은 모델 비교 실험의 ClaudeProviderClient 에서 실제 API 로 검증된 것을 그대로 옮겼다
 * (MessageCreateParams + OutputConfig 의 JSON 스키마 형식, 응답을 읽기 전에 stopReason 확인, 예외 분류).
 * 근거 문서: claude-api 스킬의 java/claude-api/README.md, java/claude-api/tool-use.md(Structured Output 의 수동 스키마 경로),
 * shared/error-codes.md.
 *
 * 이 모델에서 어기면 400 이 되는 규칙: temperature·top_p·top_k 를 보내지 않는다. thinking 파라미터는 생략한다(기본 adaptive).
 * budget_tokens 와 assistant prefill 을 쓰지 않는다. 구조화 출력은 output_config.format. max_tokens 16000, 비스트리밍.
 * 재시도는 SDK 기본값(최대 2회, 429·5xx 등)을 따르고 호출 제한 시간은 180초다.
 *
 * 키는 로그·예외 메시지에 남기지 않는다. 실패는 고정된 짧은 코드(CallException.code)로만 알린다.
 */
final class ClaudeAnalysisClient {

    static final long MAX_TOKENS = 16000L;
    static final Duration TIMEOUT = Duration.ofSeconds(180);

    /** 실패 코드만 담는 예외. 메시지·원인에 SDK 예외(응답 본문 등)를 싣지 않는다. */
    static final class CallException extends RuntimeException {
        private final String code;
        private final Integer inputTokens;
        private final Integer outputTokens;

        CallException(String code, Integer inputTokens, Integer outputTokens) {
            super(code);
            this.code = code;
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }

        String code() { return code; }
        Integer inputTokens() { return inputTokens; }
        Integer outputTokens() { return outputTokens; }
    }

    private final AnthropicClient client;

    ClaudeAnalysisClient(String apiKey) {
        this(AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(TIMEOUT).build());
    }

    /** 테스트에서 가짜 클라이언트를 넣기 위한 생성자 */
    ClaudeAnalysisClient(AnthropicClient client) {
        this.client = client;
    }

    /** 운영과 같은 요청(프롬프트 + 구조화 출력 스키마)으로 한 번 호출한다. 실패하면 CallException. */
    GeminiAnalysisService.ModelResponse call(String model, String prompt) {
        Message response;
        try {
            response = client.messages().create(buildParams(model, prompt));
        } catch (RateLimitException e) {
            throw new CallException("RATE_LIMIT", null, null);
        } catch (AnthropicServiceException e) {
            throw new CallException("HTTP_" + e.statusCode(), null, null);
        } catch (AnthropicIoException e) {
            throw new CallException(isTimeout(e) ? "TIMEOUT" : "CONNECTION_ERROR", null, null);
        } catch (AnthropicException e) {
            throw new CallException("CALL_FAILED", null, null);
        } catch (RuntimeException e) {
            throw new CallException(isTimeout(e) ? "TIMEOUT" : "CALL_FAILED", null, null);
        }
        return toResponse(response);
    }

    /** 요청 파라미터. temperature·top_p·top_k·thinking 은 설정하지 않는다. */
    static MessageCreateParams buildParams(String model, String prompt) {
        Map<String, JsonValue> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : AnalysisSchemas
                .withNoAdditionalProperties(GeminiAnalysisService.responseSchema()).entrySet()) {
            props.put(e.getKey(), JsonValue.from(e.getValue()));
        }
        JsonOutputFormat format = JsonOutputFormat.builder()
                .schema(JsonOutputFormat.Schema.builder().additionalProperties(props).build())
                .build();
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .outputConfig(OutputConfig.builder().format(format).build())
                .addUserMessage(prompt)
                .build();
    }

    /** 응답을 읽기 전에 stop_reason 을 본다. refusal·max_tokens 는 실패다(이 모델은 거절 시 서버 측 대체가 없다). */
    static GeminiAnalysisService.ModelResponse toResponse(Message response) {
        Integer in = toInt(response.usage().inputTokens());
        Integer out = toInt(response.usage().outputTokens());
        StopReason stop = response.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) throw new CallException("REFUSAL", in, out);
        if (StopReason.MAX_TOKENS.equals(stop)) throw new CallException("MAX_TOKENS", in, out);

        StringBuilder sb = new StringBuilder();
        response.content().forEach(block -> block.text().ifPresent(t -> sb.append(t.text())));
        if (sb.toString().isBlank()) throw new CallException("EMPTY_RESPONSE", in, out);
        // 생각 토큰은 output_tokens 에 포함되고 따로 보고되지 않는다 (확인 못 함 → null)
        return new GeminiAnalysisService.ModelResponse(sb.toString(), in, out, null);
    }

    private static Integer toInt(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.util.concurrent.TimeoutException
                    || t instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }
}
