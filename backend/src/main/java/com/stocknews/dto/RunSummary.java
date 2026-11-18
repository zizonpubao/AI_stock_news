package com.stocknews.dto;

import com.stocknews.entity.PipelineRun;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GET /api/runs/summary 응답. 필드 이름은 프론트와 합의된 형식이므로 바꾸지 않는다.
 * 기록이 없으면 runs 와 합계는 0, 평균·lastRunAt·lastModel 은 null.
 */
public record RunSummary(
        LocalDateTime since,
        int runs,
        int collectSuccess,
        long analysisRequested,
        long analysisSuccess,
        long analysisFailed,
        long analysisSkippedNoNews,
        Long avgAnalysisElapsedMs,
        LocalDateTime lastRunAt,
        String lastModel) {

    /**
     * 순수 함수: 주어진 실행 기록으로 요약을 계산한다. runs 는 since 이후 기록만 들어 있다고 본다.
     * - 평균 소요시간은 AI 를 실제로 요청한 실행(analysisRequested > 0)만 대상으로 한다.
     *   요청이 없던 실행(수집 실패 등)의 0 에 가까운 값이 평균을 끌어내리지 않게 하기 위함이다.
     * - lastRunAt 은 가장 늦게 끝난 실행의 종료 시각 (종료 시각이 없으면 시작 시각).
     * - lastModel 은 모델 이름이 기록된 가장 최근 실행의 모델.
     */
    public static RunSummary of(List<PipelineRun> runs, LocalDateTime since) {
        int collectSuccess = 0;
        long requested = 0, success = 0, failed = 0, skipped = 0;
        long elapsedSum = 0;
        int elapsedCount = 0;
        LocalDateTime lastRunAt = null;
        LocalDateTime lastModelAt = null;
        String lastModel = null;

        for (PipelineRun r : runs) {
            if (Boolean.TRUE.equals(r.getCollectOk())) collectSuccess++;
            requested += nz(r.getAnalysisRequested());
            success += nz(r.getAnalysisSuccess());
            failed += nz(r.getAnalysisFailed());
            skipped += nz(r.getAnalysisSkipped());
            if (nz(r.getAnalysisRequested()) > 0 && r.getAnalysisElapsedMs() != null) {
                elapsedSum += r.getAnalysisElapsedMs();
                elapsedCount++;
            }
            LocalDateTime at = r.getFinishedAt() != null ? r.getFinishedAt() : r.getStartedAt();
            if (at != null && (lastRunAt == null || at.isAfter(lastRunAt))) lastRunAt = at;
            if (r.getAnalysisModel() != null && at != null && (lastModelAt == null || at.isAfter(lastModelAt))) {
                lastModelAt = at;
                lastModel = r.getAnalysisModel();
            }
        }
        Long avg = elapsedCount == 0 ? null : Math.round((double) elapsedSum / elapsedCount);
        return new RunSummary(since, runs.size(), collectSuccess, requested, success, failed, skipped,
                avg, lastRunAt, lastModel);
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
}
