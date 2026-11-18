package com.stocknews.controller;

import com.stocknews.service.ModelExperimentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 모델 비교 실험 (관리용, X-Admin-Token 필요 — /api/admin/** 에 AdminTokenInterceptor 적용).
 * 모델 수만큼 외부 호출을 순서대로 기다리므로 응답까지 수 분 걸릴 수 있다.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/experiments")
@RequiredArgsConstructor
public class ModelExperimentController {

    private final ModelExperimentService experimentService;

    public record ModelsRequest(List<String> models) {}

    @PostMapping("/models")
    public ResponseEntity<?> compareModels(@RequestBody(required = false) ModelsRequest request) {
        List<String> models;
        try {
            models = experimentService.resolveModels(request == null ? null : request.models());
        } catch (ModelExperimentService.InvalidModelException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        log.info("모델 비교 실험 요청 - 모델 {}개", models.size());
        try {
            return ResponseEntity.ok(experimentService.run(models));
        } catch (ModelExperimentService.ExperimentBusyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }
}
