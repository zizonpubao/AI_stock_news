package com.stocknews.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 프롬프트 변형 (실험 전용). 운영 프롬프트(buildPrompt)는 건드리지 않고, 실험 코드에서 뒤에 문구를 덧붙인다.
 * 덧붙이는 순서: [운영 프롬프트] + [변형 추가 지침] + [스키마 요약(JSON_MODE·PROMPT_ONLY 수준에만)].
 */
enum PromptVariant {
    /** 운영 프롬프트 그대로 */
    BASE(""),
    /** 숫자 인용 지시를 덧붙인 것. 문구를 바꾸지 않는다(결과 문서에 원문으로 기록한다). */
    NUMBERS("\n[추가 지침]\n"
            + "주요 이슈를 쓸 때, 근거로 인용하는 기사에 수치(금액, 비율, 수량, 순위, 날짜 등)가 있으면 그 수치를 문장에 그대로 포함하세요. "
            + "기사에 없는 수치는 절대 쓰지 마세요. 수치가 없는 기사라면 수치 없이 쓰세요.");

    static final String DEFAULT = "BASE,NUMBERS";

    private final String suffix;

    PromptVariant(String suffix) {
        this.suffix = suffix;
    }

    /** 운영 프롬프트 뒤에 붙는 문구 (BASE 는 빈 문자열) */
    String suffix() {
        return suffix;
    }

    /** 운영 프롬프트에 이 변형의 추가 지침을 덧붙인 프롬프트 */
    String apply(String prompt) {
        return prompt + suffix;
    }

    /** 쉼표 목록 해석. 비었으면 기본(BASE,NUMBERS). 대소문자 무시, 중복은 한 번만, 잘못된 값은 예외. */
    static List<PromptVariant> parse(String raw) {
        String source = (raw == null || raw.isBlank()) ? DEFAULT : raw;
        Set<PromptVariant> out = new LinkedHashSet<>();
        for (String item : source.split(",")) {
            String name = item.trim();
            if (name.isEmpty()) continue;
            try {
                out.add(valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("EXPERIMENT_VARIANTS 에는 BASE, NUMBERS 만 쓸 수 있습니다.");
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("EXPERIMENT_VARIANTS 가 비었습니다.");
        return new ArrayList<>(out);
    }

    /**
     * 반복 run(1부터)에서 변형을 호출하는 순서. 변형 목록을 (run-1) 칸 돌려서 쓴다.
     * 변형이 둘이면 반복 1 은 BASE→NUMBERS, 반복 2 는 NUMBERS→BASE … 로 번갈아 나가
     * 시간대에 따른 서버 상태 차이가 한 변형에만 몰리지 않게 한다.
     */
    static List<PromptVariant> order(List<PromptVariant> variants, int run) {
        int n = variants.size();
        List<PromptVariant> out = new ArrayList<>(n);
        int offset = Math.floorMod(run - 1, n);
        for (int i = 0; i < n; i++) out.add(variants.get((offset + i) % n));
        return out;
    }
}
