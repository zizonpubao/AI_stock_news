package com.stocknews.service;

import java.util.ArrayList;
import java.util.List;

import static com.stocknews.service.FormatLevel.JSON_MODE;
import static com.stocknews.service.FormatLevel.PROMPT_ONLY;
import static com.stocknews.service.FormatLevel.SCHEMA;
import static com.stocknews.service.FormatLevel.SCHEMA_STRICT;

/** OpenAI Chat Completions 형식으로 부르는 제공자의 정의 표 (SDK 에 의존하지 않는 순수 데이터 — 설정 검증과 테스트가 쓴다). */
final class CompatProviders {

    private CompatProviders() {}

    /** 제공자 정의. baseUrl 이 null 이면 SDK 기본(OpenAI). ladder 의 첫 수준이 처음 시도할 형식 강제. */
    record Def(String name, String baseUrl, String keyEnv, List<FormatLevel> ladder, String note) {}

    static final Def NATIVE = new Def("openai", null, "OPENAI_API_KEY", List.of(SCHEMA_STRICT),
            "OpenAI 본체: json_schema + strict, 내려가기 없음 (기준선을 흐리지 않기 위해)");

    /** OpenAI 호환 제공자 표. 공식 문서에서 확인된 값만 넣는다. */
    static final List<Def> COMPATIBLE = List.of(
            new Def("upstage", "https://api.upstage.ai/v1", "UPSTAGE_API_KEY",
                    List.of(SCHEMA_STRICT, SCHEMA, JSON_MODE, PROMPT_ONLY),
                    "json_schema + strict 문서 있음(예제 모델 solar-pro4, 최저가 solar-mini4 지원 여부는 미확인)"),
            new Def("clova", "https://clovastudio.stream.ntruss.com/v1/openai/", "CLOVA_API_KEY",
                    List.of(SCHEMA, JSON_MODE, PROMPT_ONLY),
                    "호환 쪽은 response_format.type=json_schema 만 유효, strict·name 은 문서상 미인식, 구조화 출력은 HCX-007 만 지원으로 표기. "
                            + "SDK 가 name 을 필수로 요구해 name 은 보내되 서버가 무시할 수 있음"),
            new Def("groq", "https://api.groq.com/openai/v1", "GROQ_API_KEY",
                    List.of(SCHEMA_STRICT, SCHEMA, JSON_MODE, PROMPT_ONLY),
                    "json_schema strict 는 openai/gpt-oss-20b 등 일부 모델만"),
            new Def("deepseek", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                    List.of(JSON_MODE, PROMPT_ONLY),
                    "JSON 모드({\"type\":\"json_object\"})만 문서화"));

    static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Def d : COMPATIBLE) names.add(d.name());
        return names;
    }
}
