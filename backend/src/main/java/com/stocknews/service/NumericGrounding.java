package com.stocknews.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 수치 근거 일치율 (순수 함수).
 *
 * 검증을 통과한 이슈 문장에서 숫자 표현(정수·소수·퍼센트)을 뽑아, 그 이슈가 인용한 기사들의 글(제목 + 모델에게 보여 준
 * 본문 또는 요약) 안에 같은 숫자 표현이 있는지 본다. 쉼표는 제거해 비교한다(1,234 == 1234).
 * 이슈 문장과 기사 글 양쪽에서 숫자를 뽑기 전에 "천" 표기를 숫자로 바꾼다("9천810억" -> "9810억", "3천만원" -> "3000만원").
 * 인용 기사에서 못 찾은 숫자는 같은 종목의 다른 기사에 있는지 한 번 더 보아, 인용 번호 오류(OTHER_ARTICLE)와
 * 어느 기사에도 없는 숫자(NOT_FOUND)로 나눈다.
 *
 * 한계 (이 지표는 환각의 증명이 아니라 "숫자 문자열이 인용 기사에 있는가"의 점검이다):
 * - "천" 표기만 같은 값으로 맞춘다. 앞 숫자가 1~3자리이고 그 앞에 숫자·쉼표·점이 없을 때만 바꾸며("1.5천", "12,3천"은 그대로),
 *   "1천50"은 1050으로 계산한다. 백·십·만·억·조 환산은 하지 않는다.
 * - 그 밖의 표기 차이는 여전히 불일치로 잡힌다. 예: "2조"와 "20,000억", "5%"와 "5퍼센트", "1.5"와 "1.50".
 * - 한글 수사("두 개")와 단위 변환은 보지 않는다. 모델이 기사 숫자로 계산해 만든 값(합계·증감)도 불일치로 잡힌다.
 * - 날짜·순번의 숫자("3분기", "2026년")도 숫자로 센다. 인용 기사에 같은 표기가 없으면 불일치다.
 * - 인용 기사에 같은 숫자가 있어도 다른 맥락의 숫자일 수 있다(우연한 일치는 일치로 잡힌다).
 * - 기사 요약에 HTML 엔터티 등이 있으면 그 안의 숫자도 기사 쪽 숫자로 센다.
 */
final class NumericGrounding {

    static final int MAX_EXAMPLES = 5;

    /** 쉼표 묶음 정수 또는 정수, 선택적 소수부, 선택적 퍼센트(공백 한 칸 허용). */
    private static final Pattern NUMBER = Pattern.compile("(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:\\s?%)?");

    /**
     * 숫자 + 천 (+ 1~3자리 숫자). 앞 숫자가 1~3자리이고 그 앞이 숫자·쉼표·점이 아닐 때만 바꾼다.
     * 천 뒤에 숫자가 4자리 이상 이어지면("1천5000") 값이 불분명하므로 전체를 그대로 둔다.
     */
    static final Pattern CHEON = Pattern.compile("(?<![\\d,.])(\\d{1,3})천(?:(\\d{1,3})(?!\\d)|(?!\\d))");

    static final String CAT_OTHER_ARTICLE = "OTHER_ARTICLE";
    static final String CAT_NOT_FOUND = "NOT_FOUND";

    private static final ObjectMapper JSON = new ObjectMapper();

    private NumericGrounding() {}

    /** "천" 표기를 숫자 값으로 바꾼다. 9천810 -> 9810, 1천50 -> 1050(문자열 이어붙이기가 아니라 계산), 3천 -> 3000. */
    static String normalizeCheon(String text) {
        if (text == null || text.indexOf('천') < 0) return text;
        Matcher m = CHEON.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int value = Integer.parseInt(m.group(1)) * 1000 + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    record StockInput(String stockName, List<String> articleTexts, List<GeminiAnalysisService.Issue> issues) {}

    public record Unverified(String stock, String issue, String number) {}

    /** 확인되지 않은 숫자 하나의 상세. category: OTHER_ARTICLE(같은 종목의 다른 기사에는 있음) / NOT_FOUND(어느 기사에도 없음). */
    public record UnverifiedDetail(String stock, String number, String category, List<Integer> sources, String issue) {}

    /**
     * issuesEvaluated: 평가한 이슈 수(분모), issuesWithNumbers: 숫자가 하나 이상 있는 이슈,
     * numbersTotal: 이슈 문장에서 뽑은 숫자 표현의 합(이슈마다 중복 제거 후 더함), unverifiedNumbers: 그중 인용 기사에서 확인되지 않은 것의 합.
     * numbersNotInAnyArticle: 그중 같은 종목의 어느 기사에도 없는 것(NOT_FOUND)의 수. details: 확인되지 않은 숫자 전부(상한 없음).
     */
    public record Result(int issuesWithNumbers, int issuesAllConfirmed, int unverifiedNumbers, List<Unverified> examples,
                         int issuesEvaluated, int numbersTotal, int numbersNotInAnyArticle, List<UnverifiedDetail> details) {
        /** 상세 없는 결과 (기존 호출부용) */
        public Result(int issuesWithNumbers, int issuesAllConfirmed, int unverifiedNumbers, List<Unverified> examples,
                      int issuesEvaluated, int numbersTotal) {
            this(issuesWithNumbers, issuesAllConfirmed, unverifiedNumbers, examples, issuesEvaluated, numbersTotal, 0, List.of());
        }

        /** details 를 JSON 배열 문자열로. 없으면 "[]". */
        public String detailsJson() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (UnverifiedDetail d : details) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("stock", d.stock());
                o.put("number", d.number());
                o.put("category", d.category());
                o.put("sources", d.sources());
                o.put("issue", d.issue());
                list.add(o);
            }
            try {
                return JSON.writeValueAsString(list);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("상세 JSON 직렬화 실패", e);
            }
        }
    }

    /** 한 이슈의 숫자 중 인용 기사에서 확인되지 않은 것 (문장 안 등장 순서). 숫자가 없으면 빈 목록. */
    static List<String> unverifiedOf(List<String> articleTexts, GeminiAnalysisService.Issue issue) {
        List<String> numbers = extractNumbers(issue.text());
        Set<String> cited = new LinkedHashSet<>();
        for (int source : issue.sources()) {
            if (source >= 1 && source <= articleTexts.size()) cited.addAll(extractNumbers(articleTexts.get(source - 1)));
        }
        return missingFrom(numbers, cited);
    }

    private static List<String> missingFrom(List<String> numbers, Set<String> cited) {
        List<String> missing = new ArrayList<>();
        for (String n : numbers) if (!cited.contains(n)) missing.add(n);
        return missing;
    }

    /** 문자열에서 숫자 표현을 뽑아 정규화("천" 표기 환산, 쉼표·공백 제거)한다. 순서 유지, 중복 제거. */
    static List<String> extractNumbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        Matcher m = NUMBER.matcher(normalizeCheon(text));
        while (m.find()) out.add(m.group().replace(",", "").replaceAll("\\s", ""));
        return new ArrayList<>(out);
    }

    /** 기사 제목+요약(프롬프트에 들어간 길이까지)을 한 문자열로. */
    static String articleText(String title, String description, int maxDescriptionChars) {
        String d = description == null ? "" : description;
        if (d.length() > maxDescriptionChars) d = d.substring(0, maxDescriptionChars);
        return (title == null ? "" : title) + " " + d;
    }

    static Result evaluate(List<StockInput> inputs) {
        int withNumbers = 0;
        int allConfirmed = 0;
        int unverified = 0;
        int evaluated = 0;
        int numbersTotal = 0;
        int notInAny = 0;
        List<Unverified> examples = new ArrayList<>();
        List<UnverifiedDetail> details = new ArrayList<>();
        for (StockInput in : inputs) {
            Set<String> inAnyArticle = new LinkedHashSet<>();
            for (String t : in.articleTexts()) inAnyArticle.addAll(extractNumbers(t));
            for (GeminiAnalysisService.Issue issue : in.issues()) {
                evaluated++;
                List<String> numbers = extractNumbers(issue.text());
                if (numbers.isEmpty()) continue;
                withNumbers++;
                numbersTotal += numbers.size();

                List<String> missing = unverifiedOf(in.articleTexts(), issue);
                for (String n : missing) {
                    unverified++;
                    if (examples.size() < MAX_EXAMPLES) examples.add(new Unverified(in.stockName(), issue.text(), n));
                    String category = inAnyArticle.contains(n) ? CAT_OTHER_ARTICLE : CAT_NOT_FOUND;
                    if (CAT_NOT_FOUND.equals(category)) notInAny++;
                    details.add(new UnverifiedDetail(in.stockName(), n, category, List.copyOf(issue.sources()), issue.text()));
                }
                if (missing.isEmpty()) allConfirmed++;
            }
        }
        return new Result(withNumbers, allConfirmed, unverified, examples, evaluated, numbersTotal, notInAny, details);
    }
}
