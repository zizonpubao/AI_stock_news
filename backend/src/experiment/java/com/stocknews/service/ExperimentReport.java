package com.stocknews.service;

import com.stocknews.service.ExperimentStats.Aggregate;
import com.stocknews.service.ExperimentStats.RunRecord;
import com.stocknews.service.ModelExperimentRunner.ModelOutcome;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** summary.md (한국어) 만들기. 평가 문구 없이 숫자와 정의만 쓴다. */
final class ExperimentReport {

    private ExperimentReport() {}

    static String render(OffsetDateTime ranAt, String apiBase, int stocks, int articles, int promptChars, int runs,
                         List<ModelOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 모델 비교 실험 결과\n\n");
        sb.append("- 실험 일시: ").append(ranAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT))).append("\n");
        sb.append("- 입력 출처: ").append(apiBase).append(" 의 공개 API (읽기 전용)\n");
        sb.append("- 입력 규모: 뉴스가 있는 종목 ").append(stocks).append("개, 기사 ").append(articles)
                .append("건, 프롬프트 ").append(promptChars).append("자\n");
        sb.append("- 모델당 반복: ").append(runs).append("회 (같은 프롬프트를 순서대로 호출)\n\n");

        sb.append("## 호출·시간·토큰·비용\n\n");
        sb.append("| 모델 | 상태 | 호출 성공 | JSON 해석 성공 | 소요 중앙값(ms) | 최소(ms) | 최대(ms) | 평균 입력 토큰 | 평균 출력 토큰 | 평균 생각 토큰 | 평균 1회 비용(USD) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ModelOutcome o : outcomes) {
            sb.append("| ").append(o.spec().key()).append(" | ");
            if (o.skippedNoKey()) {
                sb.append("SKIPPED_NO_KEY | - | - | - | - | - | - | - | - | - |\n");
                continue;
            }
            Aggregate a = o.aggregate();
            sb.append("실행 | ").append(a.callOk()).append("/").append(a.runs())
                    .append(" | ").append(a.jsonParsed()).append("/").append(a.runs())
                    .append(" | ").append(num(a.medianMs())).append(" | ").append(num(a.minMs())).append(" | ").append(num(a.maxMs()))
                    .append(" | ").append(num(a.avgInputTokens())).append(" | ").append(num(a.avgOutputTokens()))
                    .append(" | ").append(a.avgReasoningTokens() == null ? "미보고" : num(a.avgReasoningTokens()))
                    .append(" | ").append(a.avgCostUsd() == null ? "null" : String.format(Locale.ROOT, "%.6f", a.avgCostUsd()))
                    .append(" |\n");
        }
        sb.append("\n소요 시간·토큰·비용은 호출이 성공한 반복만 집계했다. 호출 성공 = 응답 텍스트를 받음, JSON 해석 성공 = 운영 검증(parseAndValidate)이 예외 없이 끝남.\n\n");

        sb.append("## 형식·근거\n\n");
        sb.append("| 모델 | 요청 종목 합 | 응답 종목 합 | 검증 통과 종목 합 | 이슈 총수 | 유효 근거 번호가 있는 이슈 | 범위 밖 근거 번호 | 근거 없어 버려진 이슈 | 근거 유효율 | 숫자 포함 이슈 | 숫자가 모두 확인된 이슈 | 수치 근거 일치율 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ModelOutcome o : outcomes) {
            sb.append("| ").append(o.spec().key()).append(" | ");
            if (o.skippedNoKey()) {
                sb.append("SKIPPED_NO_KEY | - | - | - | - | - | - | - | - | - | - |\n");
                continue;
            }
            Aggregate a = o.aggregate();
            sb.append(a.stocksRequested()).append(" | ").append(a.stocksReturned()).append(" | ").append(a.stocksValid())
                    .append(" | ").append(a.issuesTotal()).append(" | ").append(a.issuesWithValidSources())
                    .append(" | ").append(a.invalidSourceRefs()).append(" | ").append(a.issuesDroppedNoSource())
                    .append(" | ").append(rate(a.sourceValidRate(), a.issuesWithValidSources(), a.issuesTotal()))
                    .append(" | ").append(a.issuesWithNumbers()).append(" | ").append(a.issuesAllNumbersConfirmed())
                    .append(" | ").append(rate(a.numericMatchRate(), a.issuesAllNumbersConfirmed(), a.issuesWithNumbers()))
                    .append(" |\n");
        }
        sb.append("\n형식·근거 지표는 JSON 해석에 성공한 반복을 모두 더한 값이다.\n\n");

        sb.append("## 실패한 반복의 오류 코드\n\n");
        boolean anyError = false;
        for (ModelOutcome o : outcomes) {
            if (o.skippedNoKey()) continue;
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (RunRecord r : o.runs()) if (r.errorCode() != null) counts.merge(r.errorCode(), 1, Integer::sum);
            if (counts.isEmpty()) continue;
            anyError = true;
            sb.append("- ").append(o.spec().key()).append(": ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(e.getKey()).append(" ").append(e.getValue()).append("회");
                first = false;
            }
            sb.append("\n");
        }
        if (!anyError) sb.append("없음\n");
        sb.append("\n");

        sb.append("## 모델별로 달랐던 요청 설정\n\n");
        sb.append("프롬프트 텍스트와 응답 스키마의 필드 구성, 최대 출력 한도(16000)는 모든 모델에 같다. 아래는 제공자 사정으로 달랐던 부분이다.\n\n");
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
        sb.append("- 출력 토큰은 제공자별 보고 방식을 맞춰 생각·추론 토큰을 포함한다. 생각 토큰은 보고하는 제공자만 따로 적고, 미보고는 확인하지 못했다는 뜻이다.\n");
        sb.append("- 소요 시간에는 SDK·호출 코드의 재시도와 대기가 포함된다. 같은 모델의 반복 사이에도 서버 상태에 따라 편차가 있다.\n");
        sb.append("- 같은 입력으로 반복 호출한 표본이 작아 통계적 유의성은 검증하지 않았다. 판단은 사람이 한다.\n\n");

        sb.append("## 확인되지 않은 숫자 예시\n\n");
        boolean anyExample = false;
        for (ModelOutcome o : outcomes) {
            if (o.skippedNoKey()) continue;
            List<NumericGrounding.Unverified> ex = null;
            for (RunRecord r : o.runs()) {
                if (!r.unverifiedExamples().isEmpty()) { ex = r.unverifiedExamples(); break; }
            }
            if (ex == null) continue;
            anyExample = true;
            sb.append("**").append(o.spec().key()).append("** (예시가 있는 첫 반복, 최대 ").append(NumericGrounding.MAX_EXAMPLES).append("개)\n\n");
            for (NumericGrounding.Unverified u : ex) {
                sb.append("- ").append(u.stock()).append(" / 숫자 `").append(u.number()).append("` / ").append(oneLine(u.issue())).append("\n");
            }
            sb.append("\n");
        }
        if (!anyExample) sb.append("없음 (또는 호출한 모델이 없음)\n");
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
