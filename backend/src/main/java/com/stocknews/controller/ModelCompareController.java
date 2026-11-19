package com.stocknews.controller;

import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.ModelCompareResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기사 본문 입력 모델 비교 실험 (관리용, X-Admin-Token 필요 — /api/admin/** 에 AdminTokenInterceptor 적용).
 * 수집 사이클 한 번과 모델 호출 (모델 수 x runs)번, 호출마다 간격을 끝까지 기다리므로 응답까지 십수 분 걸릴 수 있다.
 * 마지막 결과는 메모리에만 두고 GET 으로 다시 읽을 수 있다. 기사 글(includeInput)은 이 보관본에서 뺀다.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/experiments/models-with-body")
@RequiredArgsConstructor
public class ModelCompareController {

    static final int DEFAULT_RUNS = 3;
    static final int MIN_RUNS = 1;
    static final int MAX_RUNS = 5;

    private final StockScheduler stockScheduler;

    private volatile ModelCompareResult last;

    @PostMapping
    public ResponseEntity<?> run(@RequestParam(name = "runs", defaultValue = "" + DEFAULT_RUNS) int runs,
                                 @RequestParam(name = "models", required = false) String models,
                                 @RequestParam(name = "includeInput", defaultValue = "false") boolean includeInput,
                                 @RequestParam(name = "pause", required = false) Integer pause) {
        List<String> modelList;
        try {
            modelList = ModelCompareResult.parseModels(models);
        } catch (IllegalArgumentException e) {
            // 입력값을 되돌려 주지 않는 일반 메시지
            return ResponseEntity.badRequest().body(Map.of("error",
                    "models 형식이 올바르지 않습니다. 'gemini|claude|openai:모델이름' 쉼표 목록, 최대 "
                            + ModelCompareResult.MAX_MODELS + "개, 중복 불가."));
        }
        int clamped = Math.max(MIN_RUNS, Math.min(MAX_RUNS, runs));
        Integer clampedPause = pause == null ? null : Math.max(0, Math.min(ModelCompareResult.MAX_PAUSE_SECONDS, pause));
        log.info("모델 비교(본문 입력) 요청 - 모델 {}개, 모델당 {}회, 기사 글 포함 {}", modelList.size(), clamped, includeInput);
        Optional<ModelCompareResult> result = stockScheduler.runModelCompare(clamped, modelList, clampedPause, includeInput);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "이미 수집 사이클 또는 비교 실험이 진행 중입니다."));
        }
        last = result.get().withoutArticleTexts();
        return ResponseEntity.ok(result.get());
    }

    @GetMapping
    public ResponseEntity<?> lastResult() {
        ModelCompareResult r = last;
        if (r == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "아직 실행한 비교 실험이 없습니다."));
        }
        return ResponseEntity.ok(r);
    }
}
