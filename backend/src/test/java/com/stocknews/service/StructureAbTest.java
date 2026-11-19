package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 호출 구조 비교(한 번에 vs 종목별): 프롬프트 구성, 호출 순서, 다른 종목 숫자 구분, 합계, 저장 없음. 네트워크 호출 없음. */
class StructureAbTest {

    StockRepository repo = mock(StockRepository.class);
    /** 호출마다 "BATCH" / "A" / "B" 와 프롬프트 */
    List<String> kinds = new ArrayList<>();
    List<String> prompts = new ArrayList<>();

    static Stock stock(long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    static NewsArticle article(String title, String body) {
        NewsArticle a = new NewsArticle();
        a.setTitle(title);
        a.setDescription("");
        a.setBody(body);
        return a;
    }

    static Map<Long, List<NewsArticle>> newsMap() {
        return Map.of(
                1L, List.of(article("가나전자 영업이익 9.1조", "영업이익은 9.1조 원이다.")),
                2L, List.of(article("다라화학 점유율 소식", "점유율은 42% 이다.")));
    }

    static String stockJson(int index, String issue) {
        return "{\"stockIndex\":" + index + ",\"sentiment\":\"POSITIVE\",\"issues\":[{\"text\":\"" + issue
                + "\",\"sources\":[1]}],\"positives\":[\"수요\"],\"caution\":\"환율\",\"risks\":[\"원가\"]}";
    }

    /** 한 번에: A 의 문장에 B 의 기사에만 있는 42% 가 들어 있다 */
    static final String BATCH_RESPONSE = "{\"stocks\":[" + stockJson(1, "점유율 42% 달성") + "," + stockJson(2, "점유율 42% 달성") + "]}";
    static final String A_RESPONSE = "{\"stocks\":[" + stockJson(1, "영업이익 9.1조 기록") + "]}";
    static final String B_RESPONSE = "{\"stocks\":[" + stockJson(1, "점유율 42% 달성") + "]}";

    GeminiAnalysisService service(Function<String, GeminiAnalysisService.ModelResponse> byKind) {
        GeminiAnalysisService s = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                String kind = prompt.contains("== 종목 2:") ? "BATCH" : prompt.contains("가나전자") ? "A" : "B";
                kinds.add(kind);
                prompts.add(prompt);
                return byKind.apply(kind);
            }

            @Override
            ModelResponse callModel(String model, Map<String, Object> requestBody) {
                throw new AssertionError("다른 모델로 넘어가면 안 된다");
            }
        };
        ReflectionTestUtils.setField(s, "analysisModels", null);
        ReflectionTestUtils.setField(s, "anthropicApiKey", "claude-key");
        ReflectionTestUtils.setField(s, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(s, "citationAbPauseSeconds", 0);
        return s;
    }

    GeminiAnalysisService.ModelResponse ok(String kind) {
        return switch (kind) {
            case "BATCH" -> new GeminiAnalysisService.ModelResponse(BATCH_RESPONSE, 1000, 500, null);
            case "A" -> new GeminiAnalysisService.ModelResponse(A_RESPONSE, 100, 10, null);
            default -> new GeminiAnalysisService.ModelResponse(B_RESPONSE, 100, 10, null);
        };
    }

    @Test
    @DisplayName("BATCH 는 모든 종목이 든 프롬프트 한 번, PER_STOCK 은 종목마다 그 종목 기사만 든 프롬프트, 회차마다 순서가 바뀐다")
    void promptsAndOrder() {
        var s = service(this::ok);
        StructureAbResult r = s.compareStructure(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 2, false);

        assertThat(kinds).containsExactly("BATCH", "A", "B", "A", "B", "BATCH");
        assertThat(r.repetitions()).extracting(StructureAbResult.Repetition::variant)
                .containsExactly("BATCH", "PER_STOCK", "PER_STOCK", "BATCH");
        assertThat(r.repetitions()).extracting(StructureAbResult.Repetition::iteration).containsExactly(1, 1, 2, 2);
        String batch = prompts.get(0);
        assertThat(batch).contains("가나전자 영업이익 9.1조").contains("다라화학 점유율 소식");
        assertThat(prompts.get(1)).contains("가나전자 영업이익 9.1조").doesNotContain("다라화학");
        assertThat(prompts.get(2)).contains("다라화학 점유율 소식").doesNotContain("가나전자");
        assertThat(r.model()).isEqualTo("claude-haiku-5-5");
        assertThat(r.input().stocks()).isEqualTo(2);
        assertThat(r.input().articles()).isEqualTo(2);
        assertThat(r.input().articlesWithBody()).isEqualTo(2);
        assertThat(r.input().batchPromptChars()).isEqualTo(batch.length());
        assertThat(r.input().perStockPromptCharsTotal()).isEqualTo(prompts.get(1).length() + prompts.get(2).length());
    }

    @Test
    @DisplayName("다른 종목 기사에만 있는 숫자는 otherStock 으로 세고 notFound 로 세지 않는다 (한 번에 방식), 종목별 방식은 0")
    void otherStockDetectedInBatchOnly() {
        var s = service(this::ok);
        StructureAbResult r = s.compareStructure(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 1, false);

        StructureAbResult.Repetition batch = r.repetitions().get(0);
        assertThat(batch.ok()).isTrue();
        assertThat(batch.otherStock()).isEqualTo(1);
        assertThat(batch.notFound()).isZero();
        assertThat(batch.otherArticle()).isZero();
        assertThat(batch.numbersUnverified()).isEqualTo(1);
        assertThat(batch.details()).extracting(CitationAbResult.Detail::category).containsExactly("OTHER_STOCK");
        assertThat(batch.details().get(0).stock()).isEqualTo("가나전자");
        assertThat(batch.outputs()).hasSize(2);
        assertThat(batch.outputs().get(0).stockIndex()).isEqualTo(1);

        StructureAbResult.Repetition per = r.repetitions().get(1);
        assertThat(per.ok()).isTrue();
        assertThat(per.otherStock()).isZero();
        assertThat(per.numbersUnverified()).isZero();
        assertThat(per.numbersTotal()).isEqualTo(2);
        assertThat(per.stocksValidated()).isEqualTo(2);
        assertThat(per.outputs()).extracting(o -> o.stock() + "/" + o.stockIndex())
                .containsExactly("가나전자/1", "다라화학/2");

        assertThat(r.variants().get("BATCH").otherStock()).isEqualTo(1);
        assertThat(r.variants().get("PER_STOCK").otherStock()).isZero();
    }

    @Test
    @DisplayName("종목별 합계는 호출 수와 토큰을 더하고, 한 번에는 한 번 호출")
    void totalsSumCallsAndTokens() {
        var s = service(this::ok);
        StructureAbResult r = s.compareStructure(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 1, false);

        StructureAbResult.Repetition batch = r.repetitions().get(0);
        StructureAbResult.Repetition per = r.repetitions().get(1);
        assertThat(batch.apiCalls()).isEqualTo(1);
        assertThat(batch.promptTokens()).isEqualTo(1000);
        assertThat(batch.outputTokens()).isEqualTo(500);
        assertThat(per.apiCalls()).isEqualTo(2);
        assertThat(per.promptTokens()).isEqualTo(200);
        assertThat(per.outputTokens()).isEqualTo(20);
        assertThat(r.variants().get("PER_STOCK").apiCalls()).isEqualTo(2);
        assertThat(r.variants().get("PER_STOCK").meanPromptTokens()).isEqualTo(200.0);
        assertThat(r.variants().get("BATCH").apiCalls()).isEqualTo(1);
        assertThat(r.variants().get("BATCH").repetitionsOk()).isEqualTo(1);
        assertThat(r.variants().get("BATCH").medianElapsedMs()).isNotNull();
    }

    @Test
    @DisplayName("종목별에서 한 종목 호출이 실패하면 stocksFailed 1, 나머지가 성공이면 그 회차는 ok")
    void oneStockFailureKeepsRepetitionOk() {
        var s = service(kind -> {
            if ("B".equals(kind)) throw new ClaudeAnalysisClient.CallException("HTTP_500", null, null);
            return ok(kind);
        });
        StructureAbResult r = s.compareStructure(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 1, false);

        StructureAbResult.Repetition per = r.repetitions().get(1);
        assertThat(per.ok()).isTrue();
        assertThat(per.stocksFailed()).isEqualTo(1);
        assertThat(per.stocksValidated()).isEqualTo(1);
        assertThat(per.errorCode()).isEqualTo("HTTP_500");
        assertThat(per.apiCalls()).isEqualTo(2);
        assertThat(r.variants().get("PER_STOCK").stocksFailed()).isEqualTo(1);
        assertThat(r.variants().get("PER_STOCK").repetitionsOk()).isEqualTo(1);
    }

    @Test
    @DisplayName("모든 호출이 실패하면 두 변형 모두 그 회차는 실패이고 errorCode 를 남긴다")
    void allFailMeansRepetitionFailed() {
        var s = service(kind -> {
            throw new ClaudeAnalysisClient.CallException("HTTP_429", null, null);
        });
        StructureAbResult r = s.compareStructure(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 1, false);

        assertThat(r.repetitions()).allSatisfy(x -> {
            assertThat(x.ok()).isFalse();
            assertThat(x.errorCode()).isEqualTo("HTTP_429");
        });
        assertThat(r.variants().get("PER_STOCK").repetitionsFailed()).isEqualTo(1);
        assertThat(r.variants().get("BATCH").repetitionsFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 에는 아무것도 쓰지 않는다")
    void savesNothing() {
        Stock a = stock(1, "가나전자");
        var s = service(this::ok);
        s.compareStructure(List.of(a, stock(2, "다라화학")), newsMap(), 1, false);

        verifyNoInteractions(repo);
        assertThat(a.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(a.getAiAnalysis()).isNull();
    }

    @Test
    @DisplayName("includeInput 이 켜져야 기사 글이 결과에 실리고, 보관본에서는 빠진다")
    void includeInputToggle() {
        var s = service(this::ok);
        List<Stock> stocks = List.of(stock(1, "가나전자"), stock(2, "다라화학"));

        StructureAbResult off = s.compareStructure(stocks, newsMap(), 1, false);
        assertThat(off.input().articleTexts()).isNull();

        StructureAbResult on = s.compareStructure(stocks, newsMap(), 1, true);
        assertThat(on.input().articleTexts()).hasSize(2);
        assertThat(on.input().articleTexts().get(0).text()).contains("9.1조");
        assertThat(on.withoutArticleTexts().input().articleTexts()).isNull();
    }

    @Test
    @DisplayName("뉴스가 없거나 키가 없으면 모델을 부르지 않고 사유를 돌려준다")
    void notRunWhenNoNewsOrNoKey() {
        var s = service(this::ok);
        StructureAbResult none = s.compareStructure(List.of(stock(1, "가나전자")), Map.of(), 3, false);
        assertThat(none.repetitions()).isEmpty();
        assertThat(none.message()).isNotBlank();

        ReflectionTestUtils.setField(s, "anthropicApiKey", "");
        ReflectionTestUtils.setField(s, "geminiApiKey", "");
        StructureAbResult noKey = s.compareStructure(List.of(stock(1, "가나전자")), newsMap(), 3, false);
        assertThat(noKey.repetitions()).isEmpty();
        assertThat(noKey.message()).isNotBlank();
        assertThat(kinds).isEmpty();
    }
}
