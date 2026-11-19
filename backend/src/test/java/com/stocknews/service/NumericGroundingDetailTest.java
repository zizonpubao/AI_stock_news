package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.service.GeminiAnalysisService.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 천 표기 정규화, 인용 번호 오류(OTHER_ARTICLE)와 어느 기사에도 없는 숫자(NOT_FOUND)의 구분, 상세 JSON. */
class NumericGroundingDetailTest {

    private static boolean matches(String article, String issueText) {
        Issue issue = new Issue(issueText, List.of(1));
        return NumericGrounding.unverifiedOf(List.of(article), issue).isEmpty();
    }

    @Test
    @DisplayName("실제 운영 실행에서 본 네 쌍은 정규화 후 양방향으로 일치한다")
    void realPairsMatchBothDirections() {
        String[][] pairs = {
                {"영업이익 9천810억원", "영업이익 9810억원"},
                {"종가 19만1천800원", "종가 19만1800원"},
                {"매출 1조3천230억원", "매출 1조3230억원"},
                {"목표가 113만3천원", "목표가 113만3000원"},
        };
        for (String[] p : pairs) {
            assertThat(matches(p[0], p[1])).as("기사 천 / 이슈 숫자: %s", p[1]).isTrue();
            assertThat(matches(p[1], p[0])).as("기사 숫자 / 이슈 천: %s", p[0]).isTrue();
        }
    }

    @Test
    @DisplayName("천 환산은 계산이다: 1천50 -> 1050, 3천 -> 3000, 2천900원 -> 2900원, 3천만원 -> 3000만원")
    void cheonIsComputed() {
        assertThat(NumericGrounding.normalizeCheon("1천50")).isEqualTo("1050");
        assertThat(NumericGrounding.normalizeCheon("3천")).isEqualTo("3000");
        assertThat(NumericGrounding.normalizeCheon("2천900원")).isEqualTo("2900원");
        assertThat(NumericGrounding.normalizeCheon("3천만원")).isEqualTo("3000만원");
        assertThat(NumericGrounding.normalizeCheon("19만1천800원")).isEqualTo("19만1800원");
        assertThat(NumericGrounding.normalizeCheon("1조3천230억원")).isEqualTo("1조3230억원");
        assertThat(NumericGrounding.normalizeCheon("113만3천원")).isEqualTo("113만3000원");
        assertThat(NumericGrounding.extractNumbers("1천50원")).containsExactly("1050");
        assertThat(NumericGrounding.extractNumbers("3천")).containsExactly("3000");
    }

    @Test
    @DisplayName("천이 없는 글은 그대로이고, 1.5천·12,3천·1234천 같은 표기는 건드리지 않는다")
    void untouchedCases() {
        assertThat(NumericGrounding.normalizeCheon("매출 1,234억, 3.5%")).isEqualTo("매출 1,234억, 3.5%");
        assertThat(NumericGrounding.normalizeCheon("수천 명")).isEqualTo("수천 명");
        assertThat(NumericGrounding.normalizeCheon("1.5천")).isEqualTo("1.5천");
        assertThat(NumericGrounding.normalizeCheon("12,3천")).isEqualTo("12,3천");
        assertThat(NumericGrounding.normalizeCheon("1234천")).isEqualTo("1234천");
        assertThat(NumericGrounding.normalizeCheon("1천5000")).isEqualTo("1천5000");
        assertThat(NumericGrounding.normalizeCheon(null)).isNull();
        assertThat(NumericGrounding.extractNumbers("매출 1,234억, 증가율 3.5%")).containsExactly("1234", "3.5%");
    }

    @Test
    @DisplayName("다른 기사에만 있는 숫자는 미확인이지만 OTHER_ARTICLE 이고, NOT_FOUND 수에는 들어가지 않는다")
    void numberInOtherArticleIsCitationError() {
        List<String> articles = List.of("기사1 영업이익 100억", "기사2 목표가 55,000원");
        Issue issue = new Issue("목표가 55000원 상향", List.of(1));
        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(new NumericGrounding.StockInput("A", articles, List.of(issue))));
        assertThat(r.unverifiedNumbers()).isEqualTo(1);
        assertThat(r.numbersNotInAnyArticle()).isZero();
        assertThat(r.details()).hasSize(1);
        assertThat(r.details().get(0).category()).isEqualTo("OTHER_ARTICLE");
        assertThat(r.details().get(0).sources()).containsExactly(1);
    }

    @Test
    @DisplayName("어느 종목의 기사에도 없는 숫자는 NOT_FOUND 이고 두 집계에 모두 들어간다")
    void numberInNoArticleIsNotFound() {
        Issue issue = new Issue("영업이익 777억", List.of(1));
        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(
                new NumericGrounding.StockInput("A", List.of("기사 100억"), List.of(issue)),
                new NumericGrounding.StockInput("B", List.of("기사 555억"), List.of())));
        assertThat(r.unverifiedNumbers()).isEqualTo(1);
        assertThat(r.numbersNotInAnyArticle()).isEqualTo(1);
        assertThat(r.details().get(0).category()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("다른 종목의 기사에만 있는 숫자는 OTHER_STOCK 이고, 어느 기사에도 없는 숫자 수에는 들어가지 않는다 (실행 id 19 의 사례)")
    void numberInOtherStockArticle() {
        Issue issue = new Issue("3분기 연결 매출 195조3000억원", List.of(1));
        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(
                new NumericGrounding.StockInput("삼성전자", List.of("기사 거래대금 9천810억원"), List.of(issue)),
                new NumericGrounding.StockInput("삼성전자우", List.of("삼성전자의 3분기 연결 매출은 195조3000억원"), List.of())));
        assertThat(r.unverifiedNumbers()).isEqualTo(3); // 3(분기), 195, 3000
        assertThat(r.numbersNotInAnyArticle()).isZero();
        assertThat(r.details()).allMatch(d -> d.category().equals("OTHER_STOCK"));
    }

    @Test
    @DisplayName("상세는 5개 상한 없이 전부 담기고 JSON 으로 왕복된다. 없으면 빈 배열")
    void detailsAreCompleteAndRoundTrip() throws Exception {
        StringBuilder text = new StringBuilder("수치");
        for (int i = 1; i <= 8; i++) text.append(' ').append(900 + i).append("건");
        Issue issue = new Issue(text.toString(), List.of(1, 2));
        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(
                new NumericGrounding.StockInput("삼성전자", List.of("기사 1", "기사 2"), List.of(issue))));
        assertThat(r.unverifiedNumbers()).isEqualTo(8);
        assertThat(r.examples()).hasSize(NumericGrounding.MAX_EXAMPLES);
        assertThat(r.details()).hasSize(8);

        JsonNode json = new ObjectMapper().readTree(r.detailsJson());
        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(8);
        JsonNode first = json.get(0);
        assertThat(first.get("stock").asText()).isEqualTo("삼성전자");
        assertThat(first.get("number").asText()).isEqualTo("901");
        assertThat(first.get("category").asText()).isEqualTo("NOT_FOUND");
        List<Integer> sources = new ArrayList<>();
        first.get("sources").forEach(n -> sources.add(n.asInt()));
        assertThat(sources).containsExactly(1, 2);
        assertThat(first.get("issue").asText()).isEqualTo(text.toString());

        NumericGrounding.Result none = NumericGrounding.evaluate(List.of(
                new NumericGrounding.StockInput("A", List.of("기사 5"), List.of(new Issue("수치 5건", List.of(1))))));
        assertThat(none.detailsJson()).isEqualTo("[]");
    }
}
