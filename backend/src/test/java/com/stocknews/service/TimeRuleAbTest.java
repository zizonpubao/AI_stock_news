package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 시점 표현 지침(TIME_RULE): 프롬프트 구성과 유무 비교 실험(종목별 호출 두 변형). 네트워크 호출 없음. */
class TimeRuleAbTest {

    static final String OLD_GUIDELINE_2 = "2. 뉴스에 없는 수치·사실·전망을 만들어내지 마세요.\n";
    static final String NEW_GUIDELINE_2 = "2. 뉴스에 없는 수치·사실·전망을 만들어내지 마세요. " + GeminiAnalysisService.TIME_RULE + "\n";

    StockRepository repo = mock(StockRepository.class);
    /** 호출마다 "WITH"/"WITHOUT" + 종목(A/B) */
    List<String> callLog = new ArrayList<>();
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
                1L, List.of(article("가나전자 올해 영업이익", "올해 영업이익은 9.1조 원이다.")),
                2L, List.of(article("다라화학 점유율 소식", "점유율은 42% 이다.")));
    }

    static String stockJson(String issue) {
        return "{\"stockIndex\":1,\"sentiment\":\"POSITIVE\",\"issues\":[{\"text\":\"" + issue
                + "\",\"sources\":[1]}],\"positives\":[\"수요\"],\"caution\":\"환율\",\"risks\":[\"원가\"]}";
    }

    /** A 의 응답에는 기사에 없는 연도 2024 가 들어 있고, B 의 응답은 기사 속 숫자만 쓴다 */
    static final String A_RESPONSE = "{\"stocks\":[" + stockJson("2024년 영업이익 9.1조 기록") + "]}";
    static final String B_RESPONSE = "{\"stocks\":[" + stockJson("점유율 42% 달성") + "]}";

    GeminiAnalysisService service() {
        GeminiAnalysisService s = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                String rule = prompt.contains(GeminiAnalysisService.TIME_RULE) ? "WITH" : "WITHOUT";
                boolean a = prompt.contains("가나전자");
                callLog.add(rule + (a ? "-A" : "-B"));
                prompts.add(prompt);
                return new ModelResponse(a ? A_RESPONSE : B_RESPONSE, 100, 10, null);
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

    @Test
    @DisplayName("지침 없는 프롬프트는 이전 운영 프롬프트와 글자 하나 다르지 않고, 있는 프롬프트와는 지침 2 안의 문장 하나만 다르다")
    void promptDiffersOnlyByTimeRule() {
        var s = service();
        List<Stock> stocks = List.of(stock(1, "가나전자"));
        List<List<NewsArticle>> lists = List.of(newsMap().get(1L));

        String without = s.buildPrompt(stocks, lists, true, true, false);
        String with = s.buildPrompt(stocks, lists, true, true, true);

        // 이전 운영 프롬프트 = 현재 프롬프트에서 지침 2 줄을 이전 줄로 되돌린 것
        assertThat(without).isEqualTo(with.replace(NEW_GUIDELINE_2, OLD_GUIDELINE_2));
        assertThat(without).contains("\n" + OLD_GUIDELINE_2).doesNotContain(GeminiAnalysisService.TIME_RULE);
        assertThat(with).contains("\n" + NEW_GUIDELINE_2);
        assertThat(with.replace(" " + GeminiAnalysisService.TIME_RULE, "")).isEqualTo(without);
        assertThat(with.length() - without.length()).isEqualTo(1 + GeminiAnalysisService.TIME_RULE.length());
        // 지침은 여전히 7개
        assertThat(with.lines().filter(l -> l.matches("^[1-9]\\. .*")).count()).isEqualTo(7);
        assertThat(without.lines().filter(l -> l.matches("^[1-9]\\. .*")).count()).isEqualTo(7);
    }

    @Test
    @DisplayName("기존 buildPrompt 오버로드는 모두 시점 표현 지침을 포함한다")
    void olderOverloadsIncludeRule() {
        var s = service();
        List<Stock> stocks = List.of(stock(1, "가나전자"));
        List<List<NewsArticle>> lists = List.of(newsMap().get(1L));
        String with = s.buildPrompt(stocks, lists, true, true, true);

        assertThat(s.buildPrompt(stocks, lists)).isEqualTo(with);
        assertThat(s.buildPrompt(stocks, lists, true)).isEqualTo(with);
        assertThat(s.buildPrompt(stocks, lists, true, true)).isEqualTo(with);
        assertThat(s.buildPrompt(stocks, lists, false)).contains(GeminiAnalysisService.TIME_RULE);
        assertThat(s.buildPrompt(stocks, lists, true, false)).contains(GeminiAnalysisService.TIME_RULE);
    }

    @Test
    @DisplayName("두 변형 모두 종목마다 따로 부르고, WITH 프롬프트에만 지침이 있으며, 회차마다 순서가 바뀐다")
    void perStockCallsAndAlternatingOrder() {
        var s = service();
        TimeRuleAbResult r = s.compareTimeRule(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 2, false);

        assertThat(callLog).containsExactly(
                "WITHOUT-A", "WITHOUT-B", "WITH-A", "WITH-B",
                "WITH-A", "WITH-B", "WITHOUT-A", "WITHOUT-B");
        // 종목마다 그 종목 기사만 든 프롬프트
        for (String p : prompts) {
            if (p.contains("가나전자")) assertThat(p).doesNotContain("다라화학");
            else assertThat(p).doesNotContain("가나전자");
        }
        assertThat(r.repetitions()).extracting(TimeRuleAbResult.Repetition::variant)
                .containsExactly("WITHOUT_TIME_RULE", "WITH_TIME_RULE", "WITH_TIME_RULE", "WITHOUT_TIME_RULE");
        assertThat(r.repetitions()).extracting(TimeRuleAbResult.Repetition::iteration).containsExactly(1, 1, 2, 2);
        assertThat(r.repetitions()).extracting(TimeRuleAbResult.Repetition::apiCalls).containsOnly(2);
        assertThat(r.model()).isEqualTo("claude-haiku-5-5");
        assertThat(r.input().stocks()).isEqualTo(2);
        assertThat(r.input().articles()).isEqualTo(2);
        assertThat(r.input().withRulePromptCharsTotal() - r.input().withoutRulePromptCharsTotal())
                .isEqualTo(2 * (1 + GeminiAnalysisService.TIME_RULE.length()));
    }

    @Test
    @DisplayName("기사에 없는 연도가 든 문장은 yearsUnverified 와 notFound 로 세고, 합계를 변형별로 더하며, 아무것도 저장하지 않는다")
    void countsUnverifiedYearsAndSavesNothing() {
        var s = service();
        TimeRuleAbResult r = s.compareTimeRule(List.of(stock(1, "가나전자"), stock(2, "다라화학")), newsMap(), 2, false);

        for (TimeRuleAbResult.Repetition rep : r.repetitions()) {
            assertThat(rep.ok()).isTrue();
            assertThat(rep.yearsUnverified()).isEqualTo(1);
            assertThat(rep.notFound()).isEqualTo(1);
            assertThat(rep.numbersUnverified()).isEqualTo(1);
            assertThat(rep.details()).extracting(CitationAbResult.Detail::number).containsExactly("2024");
            assertThat(rep.details().get(0).category()).isEqualTo("NOT_FOUND");
            assertThat(rep.details().get(0).stock()).isEqualTo("가나전자");
            assertThat(rep.stocksValidated()).isEqualTo(2);
            assertThat(rep.issueCount()).isEqualTo(2);
            assertThat(rep.outputs()).hasSize(2);
        }
        TimeRuleAbResult.VariantTotals without = r.variants().get("WITHOUT_TIME_RULE");
        TimeRuleAbResult.VariantTotals with = r.variants().get("WITH_TIME_RULE");
        assertThat(without.repetitionsOk()).isEqualTo(2);
        assertThat(without.apiCalls()).isEqualTo(4);
        assertThat(without.yearsUnverified()).isEqualTo(2);
        assertThat(with.yearsUnverified()).isEqualTo(2);
        assertThat(with.notFound()).isEqualTo(2);
        assertThat(with.meanPromptTokens()).isEqualTo(200.0);
        verifyNoInteractions(repo);
    }

    static NewsArticle dated(String title, String body, String pubDate) {
        NewsArticle a = article(title, body);
        a.setPubDate(pubDate);
        return a;
    }

    static final String PUB = "Thu, 19 Nov 2026 11:43:00 +0900";

    @Test
    @DisplayName("지침 켜면 발행일을 읽을 수 있는 기사에 '발행:' 줄이 제목 바로 뒤에 붙고, 끄면 이전 프롬프트와 같다")
    void publishedLineOnlyWhenRuleOn() {
        var s = service();
        List<Stock> stocks = List.of(stock(1, "가나전자"));
        List<List<NewsArticle>> lists = List.of(List.of(
                dated("가나전자 올해 영업이익", "올해 3분기 영업이익은 9.1조 원이다.", PUB),
                dated("두번째 소식", "내용", "형식이 틀린 날짜"),
                article("세번째 소식", "내용")));

        String with = s.buildPrompt(stocks, lists, true, true, true);
        String without = s.buildPrompt(stocks, lists, true, true, false);

        assertThat(with).contains("[뉴스 1] 가나전자 올해 영업이익\n  발행: 2026-11-19\n  본문: 올해 3분기 영업이익은 9.1조 원이다.\n");
        assertThat(with).contains("[뉴스 2] 두번째 소식\n  본문: 내용\n");
        assertThat(with).contains("[뉴스 3] 세번째 소식\n  본문: 내용\n");
        assertThat(with.lines().filter(l -> l.startsWith("  발행: ")).count()).isEqualTo(1);
        assertThat(with).contains(NEW_GUIDELINE_2);
        assertThat(without).doesNotContain("발행").contains("\n" + OLD_GUIDELINE_2);
        assertThat(without).isEqualTo(with.replace("  발행: 2026-11-19\n", "").replace(NEW_GUIDELINE_2, OLD_GUIDELINE_2));
    }

    @Test
    @DisplayName("발행일 기준 연도·월은 기사에 근거가 있는 값으로 대조하고, 그 밖의 연도는 미확인이며 가드가 제외한다")
    void publishedDateSuffixInGrounding() {
        List<NewsArticle> arts = List.of(dated("가나전자 소식", "올해 3분기 영업이익은 9.1조 원이다. 지난해보다 늘었다.", PUB));
        List<String> texts = GeminiAnalysisService.articleTexts(arts);

        assertThat(texts.get(0)).endsWith(" 발행 2026 2025 2027 11 10 12 19");
        for (String ok : List.of("2026년 3분기 영업이익 9.1조", "지난해인 2025년보다 증가")) {
            assertThat(NumericGrounding.unverifiedOf(texts, new GeminiAnalysisService.Issue(ok, List.of(1)))).isEmpty();
        }
        assertThat(NumericGrounding.unverifiedOf(texts,
                new GeminiAnalysisService.Issue("2024년 영업이익 9.1조 기록", List.of(1)))).contains("2024");

        // 가드: 2024 가 든 이슈는 제외되고 2026 이 든 이슈는 남는다
        var s = service();
        ReflectionTestUtils.setField(s, "dropUnverifiedIssues", true);
        Stock st = stock(1, "가나전자");
        String json = "{\"stocks\":[{\"stockIndex\":1,\"sentiment\":\"POSITIVE\",\"issues\":["
                + "{\"text\":\"2024년 영업이익 9.1조 기록\",\"sources\":[1]},"
                + "{\"text\":\"2026년 3분기 영업이익 9.1조 기록\",\"sources\":[1]}],"
                + "\"positives\":[\"수요\"],\"caution\":\"환율\",\"risks\":[\"원가\"]}]}";
        GeminiAnalysisService guarded = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                return new ModelResponse(json, 1, 1, null);
            }
        };
        ReflectionTestUtils.setField(guarded, "analysisModels", null);
        ReflectionTestUtils.setField(guarded, "anthropicApiKey", "k");
        guarded.setDropUnverifiedIssues(true);
        GeminiAnalysisService.AnalysisSummary sum = guarded.analyzeBatch(List.of(st), Map.of(1L, arts));
        assertThat(sum.issuesDropped()).isEqualTo(1);
        assertThat(sum.numbersUnverified()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행일이 없거나 해석할 수 없는 기사는 글 뒤에 아무것도 덧붙이지 않는다 (이전 동작)")
    void noSuffixWithoutDate() {
        List<String> texts = GeminiAnalysisService.articleTexts(List.of(
                article("제목", "올해 3분기"), dated("제목", "올해 3분기", "엉터리"), dated("제목", "올해 3분기", null)));
        assertThat(texts).allSatisfy(t -> assertThat(t).doesNotContain("발행"));
        assertThat(texts.get(0)).isEqualTo(texts.get(1)).isEqualTo(texts.get(2));
        assertThat(GeminiAnalysisService.datesSuffix(java.time.LocalDate.of(2027, 1, 5))).isEqualTo(" 발행 2027 2026 2028 1 12 2 5");
    }

    @Test
    @DisplayName("연도 판별은 네 자리 19xx·20xx 만 센다")
    void yearPattern() {
        List<CitationAbResult.Detail> details = List.of(
                new CitationAbResult.Detail("가", "2024", "NOT_FOUND", List.of(1), "x"),
                new CitationAbResult.Detail("가", "1999", "NOT_FOUND", List.of(1), "x"),
                new CitationAbResult.Detail("가", "9.1", "NOT_FOUND", List.of(1), "x"),
                new CitationAbResult.Detail("가", "2,024", "NOT_FOUND", List.of(1), "x"),
                new CitationAbResult.Detail("가", "30", "NOT_FOUND", List.of(1), "x"),
                new CitationAbResult.Detail("가", "20240", "NOT_FOUND", List.of(1), "x"));
        assertThat(TimeRuleAbResult.countYears(details)).isEqualTo(2);
    }

    @Test
    @DisplayName("includeInput 이 아니면 기사 글을 싣지 않고, 맞으면 싣는다")
    void includeInputControlsArticleTexts() {
        var s = service();
        assertThat(s.compareTimeRule(List.of(stock(1, "가나전자")), newsMap(), 1, false).input().articleTexts()).isNull();
        assertThat(s.compareTimeRule(List.of(stock(1, "가나전자")), newsMap(), 1, true).input().articleTexts()).hasSize(1);
    }

    @Test
    @DisplayName("뉴스가 있는 종목이 없으면 모델을 부르지 않는다")
    void noNewsNoCalls() {
        var s = service();
        TimeRuleAbResult r = s.compareTimeRule(List.of(stock(9, "없는종목")), Map.of(), 2, false);
        assertThat(callLog).isEmpty();
        assertThat(r.repetitions()).isEmpty();
        assertThat(r.message()).isNotBlank();
    }
}
