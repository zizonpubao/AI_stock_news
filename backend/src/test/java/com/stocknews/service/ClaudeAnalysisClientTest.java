package com.stocknews.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.InternalServerException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.services.blocking.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Claude 호출 코드. 요청 내용과 응답·오류 처리를 검증하고 네트워크는 쓰지 않는다 (가짜 클라이언트). */
class ClaudeAnalysisClientTest {

    /** 실제 API 응답 JSON 형태를 SDK 가 읽는 방식 그대로 Message 로 만든다 (Message 빌더는 필수 필드가 많다). */
    static Message message(StopReason stop, String text) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper m = ObjectMappers.jsonMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = m.createObjectNode();
            root.put("id", "msg_test");
            root.put("type", "message");
            root.put("role", "assistant");
            root.put("model", "claude-haiku-5-5");
            root.put("stop_reason", stop.asString());
            root.putNull("stop_sequence");
            var content = root.putArray("content");
            if (text != null) content.addObject().put("type", "text").put("text", text);
            root.putObject("usage").put("input_tokens", 1200).put("output_tokens", 340);
            return m.readValue(m.writeValueAsString(root), Message.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ClaudeAnalysisClient clientReturning(Object result) {
        AnthropicClient client = mock(AnthropicClient.class);
        MessageService messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        if (result instanceof Throwable t) when(messages.create(any(MessageCreateParams.class))).thenThrow(t);
        else when(messages.create(any(MessageCreateParams.class))).thenReturn((Message) result);
        return new ClaudeAnalysisClient(client);
    }

    private static String code(Throwable t) {
        return ((ClaudeAnalysisClient.CallException) t).code();
    }

    @Test
    @DisplayName("요청은 모델·16000 토큰·JSON 스키마 출력만 보내고 temperature·top_p·top_k·thinking 은 보내지 않는다")
    void requestHasOnlyAllowedParameters() throws Exception {
        MessageCreateParams params = ClaudeAnalysisClient.buildParams("claude-haiku-5-5", "프롬프트");

        assertThat(params.maxTokens()).isEqualTo(16000L);
        assertThat(params.thinking()).isEmpty();
        assertThat(params.outputConfig()).isPresent();

        JsonNode body = ObjectMappers.jsonMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(params._body()));
        assertThat(body.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("top_p")).isFalse();
        assertThat(body.has("top_k")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("stream")).isFalse();
        assertThat(body.path("messages")).hasSize(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
        // 구식 output_format 이 아니라 output_config.format
        assertThat(body.has("output_format")).isFalse();
        JsonNode format = body.path("output_config").path("format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        // object 마다 additionalProperties=false
        assertThat(format.path("schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(format.path("schema").path("properties").path("stocks").path("items")
                .path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    @DisplayName("정상 응답은 본문 텍스트와 토큰 수를 돌려준다")
    void successReturnsTextAndTokens() {
        var r = clientReturning(message(StopReason.END_TURN, "{\"stocks\":[]}")).call("claude-haiku-5-5", "p");

        assertThat(r.text()).isEqualTo("{\"stocks\":[]}");
        assertThat(r.promptTokens()).isEqualTo(1200);
        assertThat(r.outputTokens()).isEqualTo(340);
        assertThat(r.thoughtTokens()).isNull();
    }

    @Test
    @DisplayName("거절(refusal)과 잘림(max_tokens)은 본문이 있어도 실패로 처리한다")
    void refusalAndTruncationAreFailures() {
        assertThatThrownBy(() -> clientReturning(message(StopReason.REFUSAL, "{\"stocks\":[]}")).call("m", "p"))
                .isInstanceOf(ClaudeAnalysisClient.CallException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("REFUSAL"));
        assertThatThrownBy(() -> clientReturning(message(StopReason.MAX_TOKENS, "{\"stocks\":")).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("MAX_TOKENS"));
    }

    @Test
    @DisplayName("본문이 비어 있으면 EMPTY_RESPONSE 로 실패한다")
    void emptyBodyIsFailure() {
        assertThatThrownBy(() -> clientReturning(message(StopReason.END_TURN, null)).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("EMPTY_RESPONSE"));
    }

    @Test
    @DisplayName("SDK 예외는 응답 본문·메시지 없이 짧은 코드로만 바뀐다")
    void sdkExceptionsBecomeShortCodes() {
        var server = InternalServerException.builder().statusCode(529)
                .headers(com.anthropic.core.http.Headers.builder().build())
                .body(com.anthropic.core.JsonValue.from("sk-ant-secret-body")).build();
        var rate = RateLimitException.builder().headers(com.anthropic.core.http.Headers.builder().build())
                .body(com.anthropic.core.JsonValue.from("x")).build();

        assertThatThrownBy(() -> clientReturning(server).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("HTTP_529"))
                .hasMessage("HTTP_529").hasNoCause();
        assertThatThrownBy(() -> clientReturning(rate).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("RATE_LIMIT"));
        assertThatThrownBy(() -> clientReturning(new AnthropicIoException("secret", new SocketTimeoutException())).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("TIMEOUT"));
        assertThatThrownBy(() -> clientReturning(new AnthropicIoException("secret", new java.io.IOException())).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CONNECTION_ERROR"));
        assertThatThrownBy(() -> clientReturning(new IllegalStateException("secret")).call("m", "p"))
                .satisfies(t -> assertThat(code(t)).isEqualTo("CALL_FAILED"))
                .hasMessage("CALL_FAILED");
    }
}
