package com.stocknews.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 수치 근거 일치율 (순수 함수).
 *
 * 검증을 통과한 이슈 문장에서 숫자 표현(정수·소수·퍼센트)을 뽑아, 그 이슈가 인용한 기사들의 제목+요약 안에
 * 같은 숫자 표현이 있는지 본다. 쉼표는 제거해 비교한다(1,234 == 1234). 요약은 모델에게 실제로 보여 준 길이
 * (GeminiAnalysisService.MAX_DESCRIPTION_CHARS)까지만 대조한다.
 *
 * 한계 (이 지표는 환각의 증명이 아니라 "숫자 문자열이 인용 기사에 있는가"의 점검이다):
 * - 표기가 달라도 같은 값은 불일치로 잡힌다. 예: "2조"와 "20,000억", "5%"와 "5퍼센트", "1.5"와 "1.50".
 * - 한글 수사("두 개")와 단위 변환은 보지 않는다. 모델이 기사 숫자로 계산해 만든 값(합계·증감)도 불일치로 잡힌다.
 * - 날짜·순번의 숫자("3분기", "2026년")도 숫자로 센다. 인용 기사에 같은 표기가 없으면 불일치다.
 * - 인용 기사에 같은 숫자가 있어도 다른 맥락의 숫자일 수 있다(우연한 일치는 일치로 잡힌다).
 * - 기사 요약에 HTML 엔터티 등이 있으면 그 안의 숫자도 기사 쪽 숫자로 센다.
 */
final class NumericGrounding {

    static final int MAX_EXAMPLES = 5;

    /** 쉼표 묶음 정수 또는 정수, 선택적 소수부, 선택적 퍼센트(공백 한 칸 허용). */
    private static final Pattern NUMBER = Pattern.compile("(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?(?:\\s?%)?");

    private NumericGrounding() {}

    record StockInput(String stockName, List<String> articleTexts, List<GeminiAnalysisService.Issue> issues) {}

    public record Unverified(String stock, String issue, String number) {}

    /**
     * issuesEvaluated: 평가한 이슈 수(분모), issuesWithNumbers: 숫자가 하나 이상 있는 이슈,
     * numbersTotal: 이슈 문장에서 뽑은 숫자 표현의 합(이슈마다 중복 제거 후 더함), unverifiedNumbers: 그중 인용 기사에서 확인되지 않은 것의 합.
     */
    public record Result(int issuesWithNumbers, int issuesAllConfirmed, int unverifiedNumbers, List<Unverified> examples,
                         int issuesEvaluated, int numbersTotal) {}

    /** 한 이슈의 숫자 중 인용 기사에서 확인되지 않은 것 (문장 안 등장 순서). 숫자가 없으면 빈 목록. */
    static List<String> unverifiedOf(List<String> articleTexts, GeminiAnalysisService.Issue issue) {
        List<String> numbers = extractNumbers(issue.text());
        Set<String> cited = new LinkedHashSet<>();
        for (int source : issue.sources()) {
            if (source >= 1 && source <= articleTexts.size()) cited.addAll(extractNumbers(articleTexts.get(source - 1)));
        }
        List<String> missing = new ArrayList<>();
        for (String n : numbers) if (!cited.contains(n)) missing.add(n);
        return missing;
    }

    /** 문자열에서 숫자 표현을 뽑아 정규화(쉼표·공백 제거)한다. 순서 유지, 중복 제거. */
    static List<String> extractNumbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
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
        List<Unverified> examples = new ArrayList<>();
        for (StockInput in : inputs) {
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
                }
                if (missing.isEmpty()) allConfirmed++;
            }
        }
        return new Result(withNumbers, allConfirmed, unverified, examples, evaluated, numbersTotal);
    }
}
