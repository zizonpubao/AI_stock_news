package com.stocknews.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stocknews.service.ProviderClient.ProviderResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 형식 강제 단계 내려가기, 응답에서 JSON 뽑기, 스키마 요약 문구 (모두 순수 함수). */
final class FormatControl {

    private static final ObjectMapper PARSER = new ObjectMapper();
    private static final int MAX_CANDIDATES = 50;

    private FormatControl() {}

    /** 내려가기를 마친 결과. attempts 는 "수준:OK" 또는 "수준:오류코드" 의 시도 순서. */
    record Outcome(ProviderResult result, FormatLevel level, List<String> attempts) {}

    /**
     * ladder 의 첫 수준부터 호출한다. 결과가 400 계열 거부(HTTP_400, HTTP_422)일 때만 한 단계 내려가 다시 부른다.
     * 그 밖의 오류(401/403/404/429/5xx/타임아웃 등)와 성공은 그대로 돌려주고, 마지막 수준이면 거부 결과를 그대로 돌려준다.
     */
    static Outcome execute(List<FormatLevel> ladder, Function<FormatLevel, ProviderResult> attempt) {
        if (ladder.isEmpty()) throw new IllegalArgumentException("형식 강제 단계가 비어 있습니다.");
        List<String> attempts = new ArrayList<>();
        int i = 0;
        while (true) {
            FormatLevel level = ladder.get(i);
            ProviderResult r = attempt.apply(level);
            String code = r.errorCode() != null ? r.errorCode() : (r.text() == null ? "EMPTY_RESPONSE" : "OK");
            attempts.add(level.name() + ":" + code);
            if (r.errorCode() != null && FormatLevel.isFormatRejection(r.errorCode()) && i + 1 < ladder.size()) {
                i++;
                continue;
            }
            return new Outcome(r, level, attempts);
        }
    }

    /** json: 뽑아낸 JSON (뽑지 못하면 원문 그대로), unwrapped: 코드펜스나 앞뒤 문장을 걷어냈는가 */
    record Extracted(String json, boolean unwrapped) {}

    /**
     * 모델 응답에서 첫 JSON 객체/배열을 뽑는다. ``` 코드펜스가 있으면 첫 펜스 안쪽에서 찾고(없거나 거기에 없으면 전체에서),
     * 문자열·이스케이프를 고려해 괄호 짝을 맞추며, 실제로 JSON 으로 해석되는 첫 후보만 고른다.
     * 응답 전체가 이미 JSON 하나(앞뒤 공백만 있음)이면 unwrapped=false. 뽑지 못하면 원문을 그대로 돌려 호출 측 검증이 실패하게 둔다.
     */
    static Extracted extractJson(String text) {
        if (text == null) return new Extracted(null, false);
        String t = text.strip();
        String fenced = firstFenceBody(t);
        if (fenced != null) {
            String found = firstJson(fenced);
            if (found != null) return new Extracted(found, true);
        }
        String found = firstJson(t);
        if (found == null) return new Extracted(text, false);
        return new Extracted(found, !found.equals(t));
    }

    /** 첫 ``` 펜스 안쪽 (여는 펜스의 같은 줄은 언어 표시로 보고 버린다). 펜스가 없으면 null. */
    private static String firstFenceBody(String t) {
        int open = t.indexOf("```");
        if (open < 0) return null;
        int bodyStart = t.indexOf('\n', open);
        if (bodyStart < 0) return null;
        int close = t.indexOf("```", bodyStart);
        return close < 0 ? t.substring(bodyStart + 1) : t.substring(bodyStart + 1, close);
    }

    private static String firstJson(String s) {
        int tried = 0;
        for (int start = 0; start < s.length() && tried < MAX_CANDIDATES; start++) {
            char c = s.charAt(start);
            if (c != '{' && c != '[') continue;
            tried++;
            int end = matchingEnd(s, start);
            if (end < 0) continue;
            String candidate = s.substring(start, end);
            try {
                PARSER.readTree(candidate);
                return candidate;
            } catch (JsonProcessingException e) {
                // 다음 후보로
            }
        }
        return null;
    }

    /** start 의 여는 괄호에 짝이 맞는 닫는 괄호 바로 다음 위치. 짝이 없으면 -1. */
    private static int matchingEnd(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return i + 1;
                if (depth < 0) return -1;
            }
        }
        return -1;
    }

    /**
     * JSON_MODE·PROMPT_ONLY 에서 프롬프트 뒤에 덧붙이는 스키마 요약 문구. 모든 해당 모델에 같은 문구를 쓴다
     * (키 순서를 고정해 실행마다 같은 글자가 되게 한다). 'JSON' 이라는 단어를 포함한다 (JSON 모드가 요구하는 제공자가 있다).
     */
    static String schemaNote(Map<String, Object> schema) {
        try {
            String compact = PARSER.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(schema);
            return "\n\n[출력 형식]\n반드시 아래 JSON 스키마를 따르는 JSON 객체 하나만 출력하세요. "
                    + "코드펜스(```)나 설명 문장을 붙이지 마세요.\n" + compact + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("스키마 직렬화 실패");
        }
    }
}
