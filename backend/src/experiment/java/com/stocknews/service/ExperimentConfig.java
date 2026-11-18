package com.stocknews.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 모델 비교 실험의 실행 설정 해석 (순수 함수). 환경변수 값은 호출 측이 읽어 넘긴다. */
final class ExperimentConfig {

    // 기준선(현재 운영 모델), 2026-12-01 자동 전환 대상, 가격대가 같은 3사 최저가 모델(Claude, GPT).
    // 기본 대상에서 뺀 모델 (EXPERIMENT_MODELS 로 다시 넣을 수 있다):
    // - gemini:gemini-2.5-flash-lite : 신규 사용자에게 제공되지 않아 9회 모두 404.
    // - gemini:gemma-4-31b-it : 무료 등급만 있고 9회 중 2회만 성공(과부하·한도 초과·타임아웃)해 반복 실험 시간만 늘린다.
    static final String DEFAULT_MODELS = "gemini:gemini-2.5-flash,gemini:gemini-3.5-flash-lite,"
            + "claude:claude-haiku-5-5,openai:gpt-6-luna";
    static final int DEFAULT_RUNS = 3;
    static final int MAX_RUNS = 5;
    static final int MAX_MODELS = 8;
    /** gemini(Gemma 포함) / claude / openai / OpenAI 호환 제공자(OpenAiProviderClient.COMPATIBLE 표) */
    static final Set<String> PROVIDERS = providers();

    /** 모델 ID 는 영문·숫자·'.'·'-'·'_' 만 허용한다. 첫 글자는 영문·숫자. */
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    /** 요청 본문에 들어가는 제공자는 '/' 도 허용한다 (예: groq 의 openai/gpt-oss-20b). */
    private static final Pattern MODEL_ID_WITH_SLASH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,63}");

    private ExperimentConfig() {}

    private static Set<String> providers() {
        Set<String> p = new LinkedHashSet<>(List.of("gemini", "claude", "openai"));
        p.addAll(CompatProviders.names());
        return Set.copyOf(p);
    }

    /**
     * 모델 ID 검증. '..' 와 맨 앞·맨 뒤의 '/' 는 거부한다. 모델 ID 가 URL 경로에 들어가는 gemini 는 '/' 도 거부한다
     * (그 밖의 제공자는 요청 본문에 넣는다).
     */
    static boolean isValidModelId(String provider, String modelId) {
        if (modelId == null || modelId.contains("..") || modelId.startsWith("/") || modelId.endsWith("/")) return false;
        Pattern p = "gemini".equals(provider) ? MODEL_ID : MODEL_ID_WITH_SLASH;
        return p.matcher(modelId).matches();
    }

    record ModelSpec(String provider, String modelId) {
        String key() { return provider + ":" + modelId; }
    }

    /** "제공자:모델ID" 쉼표 목록을 해석한다. 비었으면 기본값. 중복은 한 번만. 잘못된 항목이 있으면 예외. */
    static List<ModelSpec> parseModels(String raw) {
        String source = (raw == null || raw.isBlank()) ? DEFAULT_MODELS : raw;
        Set<ModelSpec> specs = new LinkedHashSet<>();
        String[] items = source.split(",");
        for (int i = 0; i < items.length; i++) {
            String item = items[i].trim();
            if (item.isEmpty()) continue;
            int colon = item.indexOf(':');
            if (colon <= 0 || colon == item.length() - 1) {
                throw new IllegalArgumentException("모델 항목 " + (i + 1) + "번째가 '제공자:모델ID' 형식이 아닙니다.");
            }
            String provider = item.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String modelId = item.substring(colon + 1).trim();
            if (!PROVIDERS.contains(provider)) {
                throw new IllegalArgumentException("모델 항목 " + (i + 1) + "번째의 제공자는 "
                        + String.join(", ", new java.util.TreeSet<>(PROVIDERS)) + " 중 하나여야 합니다.");
            }
            if (!isValidModelId(provider, modelId)) {
                throw new IllegalArgumentException("모델 항목 " + (i + 1) + "번째의 모델 ID 는 영문·숫자·'.'·'-'·'_' 만 쓸 수 있습니다"
                        + ("gemini".equals(provider) ? "." : " ('/' 는 맨 앞·맨 뒤 제외, '..' 불가)."));
            }
            specs.add(new ModelSpec(provider, modelId));
        }
        if (specs.isEmpty()) throw new IllegalArgumentException("비교할 모델이 없습니다.");
        if (specs.size() > MAX_MODELS) throw new IllegalArgumentException("모델은 최대 " + MAX_MODELS + "개까지 지정할 수 있습니다.");
        return new ArrayList<>(specs);
    }

    /** 반복 횟수. 비었으면 기본 3, 1 미만이면 1, 최대 5. 숫자가 아니면 예외. */
    static int parseRuns(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_RUNS;
        int n;
        try {
            n = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("EXPERIMENT_RUNS 는 정수여야 합니다.");
        }
        return Math.max(1, Math.min(MAX_RUNS, n));
    }
}
