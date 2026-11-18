package com.stocknews.dto;

import com.stocknews.entity.PipelineRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 요약 계산은 순수 함수이므로 DB 없이 검증한다. */
class RunSummaryTest {

    static final LocalDateTime SINCE = LocalDateTime.of(2026, 11, 18, 18, 0, 0);

    static PipelineRun run(LocalDateTime finishedAt, boolean collectOk, String model,
                           Integer requested, Integer success, Integer failed, Integer skipped, Long elapsed) {
        PipelineRun r = new PipelineRun();
        r.setStartedAt(finishedAt.minusSeconds(30));
        r.setFinishedAt(finishedAt);
        r.setCollectOk(collectOk);
        r.setAnalysisModel(model);
        r.setAnalysisRequested(requested);
        r.setAnalysisSuccess(success);
        r.setAnalysisFailed(failed);
        r.setAnalysisSkipped(skipped);
        r.setAnalysisElapsedMs(elapsed);
        return r;
    }

    @Test
    @DisplayName("기록이 없으면 runs 0, 합계 0, 평균·마지막 시각·모델은 null")
    void emptyHistory() {
        RunSummary s = RunSummary.of(List.of(), SINCE);

        assertThat(s.since()).isEqualTo(SINCE);
        assertThat(s.runs()).isZero();
        assertThat(s.collectSuccess()).isZero();
        assertThat(s.analysisRequested()).isZero();
        assertThat(s.analysisSuccess()).isZero();
        assertThat(s.analysisFailed()).isZero();
        assertThat(s.analysisSkippedNoNews()).isZero();
        assertThat(s.avgAnalysisElapsedMs()).isNull();
        assertThat(s.lastRunAt()).isNull();
        assertThat(s.lastModel()).isNull();
    }

    @Test
    @DisplayName("여러 건의 합계와 마지막 실행·모델을 계산한다")
    void multipleRuns() {
        LocalDateTime t1 = LocalDateTime.of(2026, 11, 18, 19, 0, 41);
        LocalDateTime t2 = LocalDateTime.of(2026, 11, 18, 20, 0, 40);
        LocalDateTime t3 = LocalDateTime.of(2026, 11, 19, 8, 0, 5);
        List<PipelineRun> runs = List.of(
                run(t1, true, "gemini-2.5-flash", 10, 10, 0, 0, 20000L),
                run(t3, false, null, null, null, null, null, null),            // 수집 실패 (가장 늦음)
                run(t2, true, "gemini-3.8-flash", 8, 6, 2, 2, 30000L));

        RunSummary s = RunSummary.of(runs, SINCE);

        assertThat(s.runs()).isEqualTo(3);
        assertThat(s.collectSuccess()).isEqualTo(2);
        assertThat(s.analysisRequested()).isEqualTo(18);
        assertThat(s.analysisSuccess()).isEqualTo(16);
        assertThat(s.analysisFailed()).isEqualTo(2);
        assertThat(s.analysisSkippedNoNews()).isEqualTo(2);
        assertThat(s.lastRunAt()).isEqualTo(t3);
        // 모델이 기록된 가장 최근 실행(t2)의 모델
        assertThat(s.lastModel()).isEqualTo("gemini-3.8-flash");
    }

    @Test
    @DisplayName("평균 소요시간은 AI 를 요청한 실행만 대상으로 반올림해 계산한다")
    void averageUsesOnlyRunsThatCalledAi() {
        LocalDateTime t = LocalDateTime.of(2026, 11, 19, 9, 0, 0);
        List<PipelineRun> runs = List.of(
                run(t, true, "m", 10, 10, 0, 0, 20001L),
                run(t.plusHours(1), true, "m", 10, 9, 1, 0, 21000L),
                run(t.plusHours(2), true, null, 0, 0, 0, 10, 3L),   // 요청 없음: 평균에서 제외
                run(t.plusHours(3), false, null, null, null, null, null, null));

        RunSummary s = RunSummary.of(runs, SINCE);

        assertThat(s.avgAnalysisElapsedMs()).isEqualTo(20501L); // (20001 + 21000) / 2 = 20500.5 → 반올림
    }

    @Test
    @DisplayName("종료 시각이 없으면 시작 시각을 마지막 실행 시각으로 쓴다")
    void lastRunFallsBackToStartedAt() {
        PipelineRun r = new PipelineRun();
        r.setStartedAt(LocalDateTime.of(2026, 11, 19, 10, 0, 0));

        RunSummary s = RunSummary.of(List.of(r), SINCE);

        assertThat(s.runs()).isEqualTo(1);
        assertThat(s.lastRunAt()).isEqualTo(LocalDateTime.of(2026, 11, 19, 10, 0, 0));
    }
}
