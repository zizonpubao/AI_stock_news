package com.stocknews.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 응답 스키마 변환 (순수 함수). */
final class AnalysisSchemas {

    private AnalysisSchemas() {}

    /**
     * 운영 스키마(GeminiAnalysisService.responseSchema)를 복사하면서 모든 object 에 additionalProperties=false 를 더한다.
     * Claude(output_config.format)와 OpenAI(strict json_schema)의 구조화 출력이 object 마다 이를 요구하기 때문이다.
     * 필드·타입·required·enum 은 운영 스키마와 같고, 허용하는 응답의 의미는 달라지지 않는다.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> withNoAdditionalProperties(Map<String, Object> schema) {
        return (Map<String, Object>) walk(schema);
    }

    private static Object walk(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) copy.put((String) e.getKey(), walk(e.getValue()));
            if ("object".equals(copy.get("type")) && copy.containsKey("properties")) {
                copy.put("additionalProperties", Boolean.FALSE);
            }
            return copy;
        }
        if (node instanceof List<?> l) {
            List<Object> copy = new ArrayList<>();
            for (Object o : l) copy.add(walk(o));
            return copy;
        }
        return node;
    }
}
