package com.stocknews.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI: 공식 Java SDK(com.openai:openai-java), Chat Completions API.
 * 근거: 공식 README(https://github.com/openai/openai-java) 의 Structured outputs("수동 JSON 스키마는 ResponseFormatJsonSchema 를
 * 입력 파라미터에 설정"), Client creation, Exceptions 절, 그리고 SDK 의 javap 로 확인한 빌더 메서드
 * (ResponseFormatJsonSchema.JsonSchema.builder().name().schema().strict(), CompletionUsage.completionTokensDetails().reasoningTokens()).
 * README 는 수동 경로의 코드 예시를 주지 않으므로 이 호출은 키 없이 컴파일만 확인했다.
 * 키는 OPENAI_API_KEY. 모델 ID 는 설정으로 받는다.
 */
final class OpenAiProviderClient implements ProviderClient {

    private static final long MAX_COMPLETION_TOKENS = 16000L;

    private final String apiKey = System.getenv("OPENAI_API_KEY");
    private OpenAIClient client;

    @Override public String provider() { return "openai"; }

    @Override public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    private OpenAIClient client() {
        if (client == null) {
            client = OpenAIOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(180)).build();
        }
        return client;
    }

    @Override
    public ProviderResult call(String modelId, String prompt, Map<String, Object> schema) {
        try {
            ResponseFormatJsonSchema.JsonSchema.Schema.Builder schemaBuilder = ResponseFormatJsonSchema.JsonSchema.Schema.builder();
            for (Map.Entry<String, Object> e : ExperimentSchemas.withNoAdditionalProperties(schema).entrySet()) {
                schemaBuilder.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
            }
            ResponseFormatJsonSchema.JsonSchema jsonSchema = ResponseFormatJsonSchema.JsonSchema.builder()
                    .name("stock_analysis")
                    .schema(schemaBuilder.build())
                    .strict(true)
                    .build();
            ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                    .model(modelId)
                    .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                    .responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(jsonSchema).build())
                    .addUserMessage(prompt)
                    .build();

            ChatCompletion response = client().chat().completions().create(params);

            Integer in = null;
            Integer out = null;
            Integer reasoning = null;
            Optional<CompletionUsage> usage = response.usage();
            if (usage.isPresent()) {
                in = toInt(usage.get().promptTokens());
                out = toInt(usage.get().completionTokens()); // 추론 토큰 포함
                reasoning = usage.get().completionTokensDetails()
                        .flatMap(d -> d.reasoningTokens()).map(OpenAiProviderClient::toInt).orElse(null);
            }
            if (response.choices().isEmpty()) return new ProviderResult(null, in, out, reasoning, "EMPTY_RESPONSE");
            ChatCompletion.Choice choice = response.choices().get(0);
            if (choice.message().refusal().isPresent()) return new ProviderResult(null, in, out, reasoning, "REFUSAL");
            if (ChatCompletion.Choice.FinishReason.LENGTH.equals(choice.finishReason())) {
                return new ProviderResult(null, in, out, reasoning, "MAX_TOKENS");
            }
            String text = choice.message().content().orElse("");
            if (text.isBlank()) return new ProviderResult(null, in, out, reasoning, "EMPTY_RESPONSE");
            return new ProviderResult(text, in, out, reasoning, null);
        } catch (RateLimitException e) {
            return ProviderResult.failure("RATE_LIMIT");
        } catch (OpenAIServiceException e) {
            return ProviderResult.failure("HTTP_" + e.statusCode());
        } catch (OpenAIIoException e) {
            return ProviderResult.failure(ProviderClient.isTimeout(e) ? "TIMEOUT" : "CONNECTION_ERROR");
        } catch (OpenAIException e) {
            return ProviderResult.failure("CALL_FAILED");
        } catch (RuntimeException e) {
            return ProviderResult.failure(ProviderClient.isTimeout(e) ? "TIMEOUT" : "CALL_FAILED");
        }
    }

    @Override
    public List<String> requestSettings(String modelId) {
        return List.of(
                "Chat Completions API. temperature 등 샘플링 파라미터 미전송 (모델별 지원 여부는 확인 못 함)",
                "구조화 출력: response_format json_schema, strict=true. 운영 스키마에 object 마다 additionalProperties=false 를 더함",
                "max_completion_tokens " + MAX_COMPLETION_TOKENS + ", 비스트리밍",
                "재시도·타임아웃: SDK 기본 재시도, 호출 제한 180초",
                "출력 토큰 = completion_tokens (추론 토큰 포함, 추론 토큰은 completion_tokens_details 로 따로 기록)");
    }

    private static Integer toInt(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
