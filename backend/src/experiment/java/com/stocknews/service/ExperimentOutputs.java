package com.stocknews.service;

import com.stocknews.service.ExperimentStats.IssueOut;
import com.stocknews.service.ExperimentStats.RunRecord;
import com.stocknews.service.ExperimentStats.StockOut;
import com.stocknews.service.ModelExperimentRunner.ModelOutcome;
import com.stocknews.service.ModelExperimentRunner.VariantRuns;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 생성된 이슈 문장을 모델 x 변형별로 나란히 보기 위한 파일(outputs.json, outputs.md) 만들기 (순수 함수).
 * 모델 x 변형마다 JSON 해석에 성공한 첫 번째 반복 1개의 전체 종목을 싣는다. 관점·리스크는 싣지 않는다.
 * 기사는 제목만 싣는다(요약 본문은 넣지 않는다).
 */
final class ExperimentOutputs {

    private ExperimentOutputs() {}

    /** 종목 이름과 기사 제목 목록 (제목 순서가 근거 번호) */
    record StockInfo(String name, List<String> titles) {}

    /** 모델 x 변형에서 JSON 해석에 성공한 첫 반복. 없으면 null. */
    static RunRecord firstSuccess(VariantRuns vr) {
        for (RunRecord r : vr.runs()) if (r.jsonParsed()) return r;
        return null;
    }

    static Map<String, Object> json(OffsetDateTime ranAt, List<StockInfo> stocks, List<PromptVariant> variants,
                                    List<ModelOutcome> outcomes) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ranAt", ranAt.toString());
        root.put("note", "모델 x 변형마다 JSON 해석에 성공한 첫 반복 1개의 이슈 문장. unverifiedNumbers 는 인용 기사 제목·요약에서 확인되지 않은 숫자.");
        List<Object> st = new ArrayList<>();
        for (int i = 0; i < stocks.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stockIndex", i);
            m.put("name", stocks.get(i).name());
            m.put("articleTitles", stocks.get(i).titles());
            st.add(m);
        }
        root.put("stocks", st);
        List<Object> results = new ArrayList<>();
        for (ModelOutcome o : outcomes) {
            for (PromptVariant v : variants) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("model", o.spec().key());
                m.put("variant", v.name());
                RunRecord r = pick(o, v);
                m.put("run", r == null ? null : r.run());
                m.put("stocks", r == null ? List.of() : r.outputs());
                results.add(m);
            }
        }
        root.put("results", results);
        return root;
    }

    static String markdown(OffsetDateTime ranAt, List<StockInfo> stocks, List<PromptVariant> variants, List<ModelOutcome> outcomes) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 모델별 생성 이슈 문장\n\n");
        sb.append("- 실험 일시: ").append(ranAt).append("\n");
        sb.append("- 모델 x 프롬프트 변형마다 JSON 해석에 성공한 첫 번째 반복 1개의 이슈 문장이다(관점·리스크는 싣지 않았다). 반복 번호는 제목 옆에 적었다.\n");
        sb.append("- 문장 끝 [ ] 는 근거로 인용한 기사 번호(종목 구획 맨 위의 기사 목록 번호)다. ");
        sb.append("`(미확인 숫자: ...)` 는 문장에 나온 숫자 중 인용한 기사의 제목·요약에서 같은 표기를 찾지 못한 것이다(표기만 달라도 잡힌다).\n");

        List<String> none = new ArrayList<>();
        for (ModelOutcome o : outcomes) {
            for (PromptVariant v : variants) {
                if (pick(o, v) == null) none.add(o.spec().key() + " / " + v.name());
            }
        }
        if (!none.isEmpty()) {
            sb.append("- 성공한 반복이 없어 문장이 없는 모델·변형: ").append(String.join(", ", none)).append("\n");
        }
        sb.append("\n");

        for (int i = 0; i < stocks.size(); i++) {
            StockInfo s = stocks.get(i);
            sb.append("## ").append(i + 1).append(". ").append(oneLine(s.name())).append("\n\n");
            sb.append("기사 제목:\n\n");
            for (int j = 0; j < s.titles().size(); j++) {
                sb.append(j + 1).append(". ").append(oneLine(s.titles().get(j))).append("\n");
            }
            sb.append("\n");
            for (ModelOutcome o : outcomes) {
                for (PromptVariant v : variants) {
                    RunRecord r = pick(o, v);
                    if (r == null) continue;
                    sb.append("### ").append(o.spec().key()).append(" / ").append(v.name())
                            .append(" (반복 ").append(r.run()).append(")\n\n");
                    StockOut so = stockOut(r, i);
                    if (so == null || so.issues().isEmpty()) {
                        sb.append("- (이 종목의 이슈 없음: 응답에 없거나 검증에서 제외됨)\n\n");
                        continue;
                    }
                    for (IssueOut is : so.issues()) {
                        sb.append("- ").append(oneLine(is.text())).append(" ").append(sourcesText(is.sources()));
                        if (!is.unverified().isEmpty()) {
                            sb.append(" (미확인 숫자: ").append(String.join(", ", is.unverified())).append(")");
                        }
                        sb.append("\n");
                    }
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    private static RunRecord pick(ModelOutcome o, PromptVariant v) {
        if (o.skippedNoKey()) return null;
        for (VariantRuns vr : o.variants()) if (vr.variant() == v) return firstSuccess(vr);
        return null;
    }

    private static StockOut stockOut(RunRecord r, int stockIndex) {
        if (r.outputs() == null) return null;
        for (StockOut so : r.outputs()) if (so.stockIndex() == stockIndex) return so;
        return null;
    }

    private static String sourcesText(List<Integer> sources) {
        List<String> s = new ArrayList<>();
        for (Integer n : sources) s.add(String.valueOf(n));
        return "[" + String.join(", ", s) + "]";
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
