package com.stocknews.service;

import com.stocknews.service.ExperimentStats.Aggregate;
import com.stocknews.service.ExperimentStats.RunRecord;
import com.stocknews.service.ModelExperimentRunner.ModelOutcome;
import com.stocknews.service.ModelExperimentRunner.VariantRuns;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** summary.md (한국어) 만들기. 평가 문구 없이 숫자와 정의만 쓴다. */
final class ExperimentReport {

    private ExperimentReport() {}

    static String render(OffsetDateTime ranAt, String apiBase, int stocks, int articles, Map<PromptVariant, Integer> promptChars,
                         int runs, List<PromptVariant> variants, List<ModelOutcome> outcomes, String schemaNote) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 모델 비교 실험 결과\n\n");
        sb.append("- 실험 일시: ").append(ranAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT))).append("\n");
        sb.append("- 입력 출처: ").append(apiBase).append(" 의 공개 API (읽기 전용)\n");
        sb.append("- 입력 규모: 뉴스가 있는 종목 ").append(stocks).append("개, 기사 ").append(articles)
                .append("건, 프롬프트 ");
        boolean firstChars = true;
        for (PromptVariant v : variants) {
            if (!firstChars) sb.append(", ");
            sb.append(v.name()).append(" ").append(promptChars.get(v)).append("자");
            firstChars = false;
        }
        sb.append("\n");
        sb.append("- 프롬프트 변형: ").append(variantNames(variants)).append("\n");
        sb.append("- 모델·변형당 반복: ").append(runs).append("회\n\n");

        appendVariantSection(sb, variants, runs);

        sb.append("## 호출·시간·토큰·비용\n\n");
        List<String> h1 = List.of("모델", "프롬프트", "상태", "호출 성공", "JSON 해석 성공", "소요 중앙값(ms)", "최소(ms)", "최대(ms)",
                "평균 입력 토큰", "평균 출력 토큰", "평균 생각 토큰", "평균 1회 비용(USD)");
        List<List<String>> r1 = new ArrayList<>();
        for (ModelOutcome o : outcomes) {
            for (PromptVariant v : variants) {
                Aggregate a = aggregateOf(o, v);
                if (a == null) {
                    r1.add(row(o.spec().key(), v.name(), "SKIPPED_NO_KEY", "-", "-", "-", "-", "-", "-", "-", "-", "-"));
                    continue;
                }
                r1.add(row(o.spec().key(), v.name(), "실행", a.callOk() + "/" + a.runs(), a.jsonParsed() + "/" + a.runs(),
                        num(a.medianMs()), num(a.minMs()), num(a.maxMs()), num(a.avgInputTokens()), num(a.avgOutputTokens()),
                        a.avgReasoningTokens() == null ? "미보고" : num(a.avgReasoningTokens()),
                        a.avgCostUsd() == null ? "null" : String.format(Locale.ROOT, "%.6f", a.avgCostUsd())));
            }
        }
        sb.append(table(h1, r1));
        sb.append("\n소요 시간·토큰·비용은 호출이 성공한 반복만 집계했다. 호출 성공 = 응답 텍스트를 받음, JSON 해석 성공 = 운영 검증(parseAndValidate)이 예외 없이 끝남. 모든 지표는 모델 x 프롬프트 변형 단위다.\n\n");

        sb.append("## 형식·근거\n\n");
        List<String> h2 = List.of("모델", "프롬프트", "형식 강제", "코드펜스 제거", "요청 종목 합", "응답 종목 합", "검증 통과 종목 합",
                "이슈 총수", "유효 근거 번호가 있는 이슈", "범위 밖 근거 번호", "근거 없어 버려진 이슈", "근거 유효율",
                "수치 검사 대상 이슈", "숫자 포함 이슈", "숫자 포함 비율", "숫자가 모두 확인된 이슈", "수치 근거 일치율",
                "이슈 문장의 숫자 총수", "확인되지 않은 숫자 수");
        List<List<String>> r2 = new ArrayList<>();
        for (ModelOutcome o : outcomes) {
            for (PromptVariant v : variants) {
                Aggregate a = aggregateOf(o, v);
                if (a == null) {
                    List<String> dash = new ArrayList<>();
                    dash.add(o.spec().key());
                    dash.add(v.name());
                    dash.add("SKIPPED_NO_KEY");
                    while (dash.size() < h2.size()) dash.add("-");
                    r2.add(dash);
                    continue;
                }
                r2.add(row(o.spec().key(), v.name(), levels(a.formatLevels()), a.unwrappedRuns() + "/" + a.jsonParsed(),
                        String.valueOf(a.stocksRequested()), String.valueOf(a.stocksReturned()), String.valueOf(a.stocksValid()),
                        String.valueOf(a.issuesTotal()), String.valueOf(a.issuesWithValidSources()),
                        String.valueOf(a.invalidSourceRefs()), String.valueOf(a.issuesDroppedNoSource()),
                        rate(a.sourceValidRate(), a.issuesWithValidSources(), a.issuesTotal()),
                        String.valueOf(a.issuesEvaluated()), String.valueOf(a.issuesWithNumbers()),
                        rate(a.numberInclusionRate(), a.issuesWithNumbers(), a.issuesEvaluated()),
                        String.valueOf(a.issuesAllNumbersConfirmed()),
                        rate(a.numericMatchRate(), a.issuesAllNumbersConfirmed(), a.issuesWithNumbers()),
                        String.valueOf(a.numbersTotal()), String.valueOf(a.unverifiedNumbers())));
            }
        }
        sb.append(table(h2, r2));
        sb.append("\n형식·근거 지표는 JSON 해석에 성공한 반복을 모두 더한 값이다.\n\n");

        appendComparison(sb, variants, outcomes);

        sb.append("## 형식 강제 시도 이력\n\n");
        sb.append("반복마다 어떤 수준을 시도했고 어떻게 끝났는지(수준:결과, '>' 는 한 단계 내려가 다시 시도)를 횟수와 함께 적는다.\n\n");
        for (ModelOutcome o : outcomes) {
            if (o.skippedNoKey()) continue;
            for (VariantRuns vr : o.variants()) {
                Map<String, Integer> trails = vr.aggregate().formatTrails();
                sb.append("- ").append(o.spec().key()).append(" / ").append(vr.variant().name()).append(": ");
                if (trails == null || trails.isEmpty()) {
                    sb.append("기록 없음\n");
                    continue;
                }
                boolean firstTrail = true;
                for (Map.Entry<String, Integer> e : trails.entrySet()) {
                    if (!firstTrail) sb.append(", ");
                    sb.append(e.getKey()).append(" ").append(e.getValue()).append("회");
                    firstTrail = false;
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        sb.append("## 실패한 반복의 오류 코드\n\n");
        boolean anyError = false;
        for (ModelOutcome o : outcomes) {
            if (o.skippedNoKey()) continue;
            for (VariantRuns vr : o.variants()) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                for (RunRecord r : vr.runs()) if (r.errorCode() != null) counts.merge(r.errorCode(), 1, Integer::sum);
                if (counts.isEmpty()) continue;
                anyError = true;
                sb.append("- ").append(o.spec().key()).append(" / ").append(vr.variant().name()).append(": ");
                boolean first = true;
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append(e.getKey()).append(" ").append(e.getValue()).append("회");
                    first = false;
                }
                sb.append("\n");
            }
        }
        if (!anyError) sb.append("없음\n");
        sb.append("\n");

        sb.append("## 모델별로 달랐던 요청 설정\n\n");
        sb.append("프롬프트 텍스트와 응답 스키마의 필드 구성, 최대 출력 한도(16000)는 모든 모델에 같다. 다만 형식 강제 수준이 JSON_MODE·PROMPT_ONLY 로 적용된 호출에는 ");
        sb.append("프롬프트(변형의 추가 지침까지 붙인 것) 뒤에 아래 스키마 요약 문구를 덧붙였고(모든 해당 모델에 같은 문구, 순서: 운영 프롬프트, 추가 지침, 스키마 요약), 스키마로 강제된 수준(SCHEMA_STRICT·SCHEMA)의 프롬프트는 건드리지 않았다. 아래는 제공자 사정으로 달랐던 부분이다.\n\n");
        sb.append("덧붙인 문구 (").append(schemaNote.length()).append("자):\n\n````text").append(schemaNote).append("````\n\n");
        for (ModelOutcome o : outcomes) {
            sb.append("**").append(o.spec().key()).append("**\n\n");
            for (String s : o.requestSettings()) sb.append("- ").append(s).append("\n");
            sb.append("\n");
        }

        sb.append("## 적용한 가격\n\n");
        boolean anyPrice = false;
        for (ModelOutcome o : outcomes) {
            if (o.price() == null) continue;
            anyPrice = true;
            sb.append("- ").append(o.spec().key()).append(": 입력 ").append(o.price().inputPerMTok()).append(", 출력 ")
                    .append(o.price().outputPerMTok()).append(" USD / 100만 토큰 (출처 ").append(o.price().source())
                    .append(", 확인일 ").append(o.price().checkedAt()).append(")");
            if (!o.price().note().isBlank()) sb.append(" — ").append(o.price().note());
            sb.append("\n");
        }
        for (ModelOutcome o : outcomes) {
            if (o.price() == null) sb.append("- ").append(o.spec().key()).append(": 가격표에 없음 → 비용 null\n");
        }
        if (!anyPrice) sb.append("가격표에 있는 모델이 없다.\n");
        sb.append("\n");

        sb.append("## 지표 정의와 한계\n\n");
        sb.append("- 근거 지표(이슈 총수, 유효 근거 번호가 있는 이슈, 범위 밖 근거 번호, 버려진 이슈)는 운영의 모델 비교(ModelExperimentService)와 같은 정의다.\n");
        sb.append("- 근거 유효율 = 유효 근거 번호가 있는 이슈 / 이슈 총수.\n");
        sb.append("- 수치 근거 일치율: 검증을 통과한 이슈 문장에서 숫자 표현(정수·소수·퍼센트, 쉼표 제거)을 뽑아, 그 이슈가 인용한 기사의 제목+요약(프롬프트에 들어간 길이까지) 안에 같은 숫자 표현이 있는지 문자열로 확인한다. 일치율 = 숫자가 모두 확인된 이슈 / 숫자가 들어 있는 이슈.\n");
        sb.append("- 수치 근거 일치율의 한계: ① \"2조\"와 \"20,000억\"처럼 표기가 달라도 같은 값이면 불일치로 잡힌다. ② 모델이 기사 숫자로 계산한 합계·증감도 불일치로 잡힌다. ③ 날짜·분기·순번의 숫자도 포함된다. ④ 인용 기사에 같은 숫자가 있으면 맥락이 달라도 일치로 잡힌다. 따라서 환각의 증명이 아니라 숫자 문자열이 인용 기사에 있는지의 점검이다.\n");
        sb.append("- 수치 검사 대상 이슈 = 검증을 통과해 숫자 검사에 들어간 이슈 수. 이슈 총수(모델이 낸 이슈 전부, 근거 없어 버려진 것 포함)와 다를 수 있어 숫자 포함 비율의 분모는 이 값이다.\n");
        sb.append("- 숫자 포함 비율 = 숫자 포함 이슈 / 수치 검사 대상 이슈. 숫자 포함 이슈 = 이슈 문장에서 숫자 표현이 하나 이상 나온 이슈.\n");
        sb.append("- 이슈 문장의 숫자 총수 = 숫자 포함 이슈마다 (중복을 뺀) 숫자 표현의 개수를 모두 더한 값. 확인되지 않은 숫자 수 = 그중 인용 기사 제목·요약에 같은 표기가 없는 숫자의 합(이슈 단위가 아니라 숫자 단위).\n");
        sb.append("- 출력 토큰은 제공자별 보고 방식을 맞춰 생각·추론 토큰을 포함한다. 생각 토큰은 보고하는 제공자만 따로 적고, 미보고는 확인하지 못했다는 뜻이다.\n");
        sb.append("- 소요 시간에는 SDK·호출 코드의 재시도와 대기가 포함된다. 같은 모델의 반복 사이에도 서버 상태에 따라 편차가 있다.\n");
        sb.append("- 형식 강제 수준이 모델마다 달라서 \"JSON 해석 성공\"의 의미가 같지 않다. SCHEMA_STRICT·SCHEMA 는 제공자가 형식을 강제한 결과이고, JSON_MODE·PROMPT_ONLY 는 프롬프트 지시와 코드펜스·앞뒤 문장 제거(첫 JSON 객체/배열 추출)에 기댄 결과다. 수준이 다른 모델의 형식 성공률을 같은 잣대로 비교하지 않는다.\n");
        sb.append("- 같은 입력으로 반복 호출한 표본이 작아 통계적 유의성은 검증하지 않았다. 판단은 사람이 한다.\n\n");

        sb.append("## 확인되지 않은 숫자 예시\n\n");
        boolean anyExample = false;
        for (ModelOutcome o : outcomes) {
            if (o.skippedNoKey()) continue;
            for (VariantRuns vr : o.variants()) {
                List<NumericGrounding.Unverified> ex = null;
                for (RunRecord r : vr.runs()) {
                    if (!r.unverifiedExamples().isEmpty()) { ex = r.unverifiedExamples(); break; }
                }
                if (ex == null) continue;
                anyExample = true;
                sb.append("**").append(o.spec().key()).append(" / ").append(vr.variant().name())
                        .append("** (예시가 있는 첫 반복, 최대 ").append(NumericGrounding.MAX_EXAMPLES).append("개)\n\n");
                for (NumericGrounding.Unverified u : ex) {
                    sb.append("- ").append(u.stock()).append(" / 숫자 `").append(u.number()).append("` / ").append(oneLine(u.issue())).append("\n");
                }
                sb.append("\n");
            }
        }
        if (!anyExample) sb.append("없음 (또는 호출한 모델이 없음)\n");
        return sb.toString();
    }

    // ===== 표 만들기: 머리글·구분선·값 행의 열 개수를 한 곳에서 맞춘다 =====

    private static List<String> row(String... cells) {
        return List.of(cells);
    }

    /** 마크다운 표. 값 행의 열 개수가 머리글과 다르면 예외 (열이 밀린 표를 내보내지 않는다). */
    static String table(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", headers)).append(" |\n");
        sb.append("|").append("---|".repeat(headers.size())).append("\n");
        for (List<String> r : rows) {
            if (r.size() != headers.size()) {
                throw new IllegalStateException("표의 열 개수 불일치: 머리글 " + headers.size() + ", 값 " + r.size());
            }
            sb.append("| ").append(String.join(" | ", r.stream().map(ExperimentReport::cell).toList())).append(" |\n");
        }
        return sb.toString();
    }

    private static String cell(String s) {
        return s == null ? "-" : s.replace("|", "\\|").replaceAll("\\s+", " ");
    }

    private static Aggregate aggregateOf(ModelOutcome o, PromptVariant v) {
        if (o.skippedNoKey()) return null;
        for (VariantRuns vr : o.variants()) if (vr.variant() == v) return vr.aggregate();
        return null;
    }

    private static String variantNames(List<PromptVariant> variants) {
        List<String> names = new ArrayList<>();
        for (PromptVariant v : variants) names.add(v.name());
        return String.join(", ", names);
    }

    private static void appendVariantSection(StringBuilder sb, List<PromptVariant> variants, int runs) {
        sb.append("## 프롬프트 변형과 호출 순서\n\n");
        sb.append("- BASE: 수치 인용 지침이 없는 프롬프트(GeminiAnalysisService.buildPrompt(.., false))를 보냈다.\n");
        sb.append("- NUMBERS: 그 뒤에 아래 [추가 지침]을 그대로 덧붙였다. 이 지침은 운영 상수(NUMBERS_GUIDELINE)이고, 이 프롬프트는 현재 운영 프롬프트와 같다.\n");
        sb.append("- 덧붙이는 순서: 지침 없는 프롬프트, 추가 지침(NUMBERS 만), 스키마 요약(형식 강제 수준이 JSON_MODE·PROMPT_ONLY 인 호출에만).\n");
        sb.append("- 호출 순서: 모델마다 반복 i 에서 변형을 번갈아 호출한다. 변형 목록을 (i-1)칸 돌린 순서로 부른다(변형이 BASE,NUMBERS 이면 반복 1 은 BASE 다음 NUMBERS, 반복 2 는 NUMBERS 다음 BASE). ");
        sb.append("한 변형만 몰아서 부르면 시간대에 따른 서버 상태 차이가 변형 차이에 섞이기 때문이다. 이번 실행의 변형: ")
                .append(variantNames(variants)).append(", 반복 ").append(runs).append("회.\n\n");
        sb.append("NUMBERS 에 덧붙인 원문:\n\n````text").append(PromptVariant.NUMBERS.suffix()).append("\n````\n\n");
    }

    /** 모델마다 BASE → NUMBERS 로 갈 때의 변화. 숫자만 적고 평가 문구는 쓰지 않는다. */
    private static void appendComparison(StringBuilder sb, List<PromptVariant> variants, List<ModelOutcome> outcomes) {
        sb.append("## 변형 간 비교 (BASE → NUMBERS)\n\n");
        if (!variants.contains(PromptVariant.BASE) || !variants.contains(PromptVariant.NUMBERS)) {
            sb.append("이번 실행은 변형이 ").append(variantNames(variants)).append(" 뿐이라 비교하지 않았다.\n\n");
            return;
        }
        List<String> h = List.of("모델", "숫자 포함 비율", "수치 근거 일치율", "확인되지 않은 숫자 / 이슈 문장의 숫자 총수", "평균 출력 토큰", "소요 중앙값(ms)");
        List<List<String>> rows = new ArrayList<>();
        for (ModelOutcome o : outcomes) {
            Aggregate b = aggregateOf(o, PromptVariant.BASE);
            Aggregate n = aggregateOf(o, PromptVariant.NUMBERS);
            if (b == null || n == null) {
                rows.add(row(o.spec().key(), "-", "-", "-", "-", "-"));
                continue;
            }
            rows.add(row(o.spec().key(),
                    pair(pct(b.numberInclusionRate()), pct(n.numberInclusionRate())),
                    pair(pct(b.numericMatchRate()), pct(n.numericMatchRate())),
                    pair(b.unverifiedNumbers() + "/" + b.numbersTotal(), n.unverifiedNumbers() + "/" + n.numbersTotal()),
                    pair(num(b.avgOutputTokens()), num(n.avgOutputTokens())),
                    pair(num(b.medianMs()), num(n.medianMs()))));
        }
        sb.append(table(h, rows));
        sb.append("\n각 칸은 'BASE 값 → NUMBERS 값'이다. '-' 는 호출하지 않았거나 해당 값을 낼 표본이 없다는 뜻이다. 표본이 작아(모델·변형당 반복 수만큼) 차이의 유의성은 검증하지 않았다.\n\n");
    }

    private static String pair(String a, String b) {
        return a + " → " + b;
    }

    private static String pct(Double r) {
        return r == null ? "-" : String.format(Locale.ROOT, "%.1f%%", r * 100);
    }

    /** 적용된 형식 강제 수준 표기. 한 가지뿐이면 이름만, 섞였으면 횟수와 함께 */
    private static String levels(Map<String, Integer> m) {
        if (m == null || m.isEmpty()) return "-";
        if (m.size() == 1) return m.keySet().iterator().next();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append(" ").append(e.getValue()).append("회");
        }
        return sb.toString();
    }

    private static String num(Number n) {
        if (n == null) return "-";
        if (n instanceof Double d) return String.format(Locale.ROOT, "%.1f", d);
        return String.valueOf(n);
    }

    private static String rate(Double r, int numerator, int denominator) {
        if (r == null) return "-";
        return String.format(Locale.ROOT, "%.1f%% (%d/%d)", r * 100, numerator, denominator);
    }

    private static String oneLine(String s) {
        return s.replace("|", "\\|").replaceAll("\\s+", " ");
    }
}
