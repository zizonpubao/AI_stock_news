package com.stocknews.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.RateLimitException;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.completions.CompletionUsage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.stocknews.service.FormatLevel.SCHEMA;
import static com.stocknews.service.FormatLevel.SCHEMA_STRICT;

/**
 * OpenAI Chat Completions: 공식 Java SDK(com.openai:openai-java). OpenAI 본체와, base URL 과 키 환경변수만 바꿔 부르는
 * OpenAI 호환 제공자(upstage, clova, groq, deepseek)를 같은 클래스로 다룬다. 호환 제공자는 {@link CompatProviders#COMPATIBLE} 표 한 곳에서 정의한다.
 *
 * 근거: 공식 README(https://github.com/openai/openai-java) 의 Structured outputs("수동 JSON 스키마는 ResponseFormatJsonSchema 를
 * 입력 파라미터에 설정"), Client creation 절의 설정 표(baseUrl 항목 — 클라이언트 빌더의 baseUrl(String)), Exceptions 절,
 * 그리고 SDK 의 javap 로 확인한 빌더 메서드(OpenAIOkHttpClient.Builder.baseUrl(String),
 * ChatCompletionCreateParams.Builder.responseFormat(ResponseFormatJsonObject / ResponseFormatJsonSchema),
 * ResponseFormatJsonSchema.JsonSchema.builder().name().schema().strict(), CompletionUsage.completionTokensDetails().reasoningTokens()).
 * README 는 수동 경로의 코드 예시를 주지 않으므로 실제 키 호출은 확인하지 못했고, 요청 모양은 로컬 가짜 서버로 확인했다(테스트).
 */
final class OpenAiProviderClient implements ProviderClient {

    private static final long MAX_COMPLETION_TOKENS = 16000L;
    static final String SCHEMA_NAME = "stock_analysis";

    private final CompatProviders.Def def;
    private final String apiKey;
    private OpenAIClient client;

    /** 키는 정의의 환경변수에서 읽는다. */
    OpenAiProviderClient(CompatProviders.Def def) {
        this(def, System.getenv(def.keyEnv()));
    }

    /** 테스트용: 키를 직접 받는다 (가짜 값). */
    OpenAiProviderClient(CompatProviders.Def def, String apiKey) {
        this.def = def;
        this.apiKey = apiKey;
    }

    @Override public String provider() { return def.name(); }

    @Override public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    @Override public List<FormatLevel> formatLadder(String modelId) { return def.ladder(); }

    private OpenAIClient client() {
        if (client == null) {
            OpenAIOkHttpClient.Builder b = OpenAIOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofSeconds(180));
            if (def.baseUrl() != null) b.baseUrl(def.baseUrl());
            client = b.build();
        }
        return client;
    }

    @Override
    public ProviderResult call(String modelId, String prompt, Map<String, Object> schema, FormatLevel level) {
        try {
            ChatCompletionCreateParams.Builder pb = ChatCompletionCreateParams.builder()
                    .model(modelId)
                    .maxCompletionTokens(MAX_COMPLETION_TOKENS)
                    .addUserMessage(prompt);
            switch (level) {
                case SCHEMA_STRICT, SCHEMA -> {
                    ResponseFormatJsonSchema.JsonSchema.Schema.Builder schemaBuilder = ResponseFormatJsonSchema.JsonSchema.Schema.builder();
                    for (Map.Entry<String, Object> e : AnalysisSchemas.withNoAdditionalProperties(schema).entrySet()) {
                        schemaBuilder.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
                    }
                    ResponseFormatJsonSchema.JsonSchema.Builder js = ResponseFormatJsonSchema.JsonSchema.builder()
                            .name(SCHEMA_NAME)
                            .schema(schemaBuilder.build());
                    if (level == SCHEMA_STRICT) js.strict(true);
                    pb.responseFormat(ResponseFormatJsonSchema.builder().jsonSchema(js.build()).build());
                }
                case JSON_MODE -> pb.responseFormat(ResponseFormatJsonObject.builder().build());
                case PROMPT_ONLY -> { /* response_format 을 보내지 않는다 */ }
            }

            ChatCompletion response = client().chat().completions().create(pb.build());

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
        List<String> s = new ArrayList<>();
        s.add("Chat Completions API" + (def.baseUrl() == null ? "" : ", base URL " + def.baseUrl() + " (openai-java 의 baseUrl)")
                + ". temperature 등 샘플링 파라미터 미전송 (모델별 지원 여부는 확인 못 함)");
        StringBuilder ladder = new StringBuilder();
        for (FormatLevel l : def.ladder()) {
            if (ladder.length() > 0) ladder.append(" → ");
            ladder.append(l.name());
        }
        s.add("형식 강제 순서(처음 → 400 계열 거부 때 한 단계씩 내려감): " + ladder + ". " + def.note());
        s.add("수준별 요청: SCHEMA_STRICT = response_format json_schema(name=" + SCHEMA_NAME + ", strict=true), SCHEMA = json_schema(strict 없음, name 은 SDK 필수라 보냄), "
                + "JSON_MODE = response_format json_object, PROMPT_ONLY = response_format 없음. 스키마 수준은 운영 스키마에 object 마다 additionalProperties=false 를 더함");
        s.add("max_completion_tokens " + MAX_COMPLETION_TOKENS + ", 비스트리밍 (제공자가 이 파라미터 이름·한도를 받는지는 확인 못 함)");
        s.add("재시도·타임아웃: SDK 기본 재시도, 호출 제한 180초");
        s.add("출력 토큰 = completion_tokens (추론 토큰 포함, 추론 토큰은 completion_tokens_details 로 따로 기록)");
        return s;
    }

    private static Integer toInt(long v) {
        return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
    }
}
