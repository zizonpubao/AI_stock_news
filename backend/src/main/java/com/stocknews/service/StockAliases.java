package com.stocknews.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 종목 별칭 사전. 기사 제목이 종목명과 다른 표기를 쓰는 종목(예: NAVER → 네이버)의 보조 검색어.
 * 설정(news.aliases)에서 읽으며 키는 종목코드 6자리 문자열이다.
 */
@Component
@ConfigurationProperties(prefix = "news.aliases")
@Getter
@Setter
public class StockAliases {

    private boolean enabled = true;
    private Map<String, List<String>> byCode = new LinkedHashMap<>();

    /** 항상 빈 목록을 주는 인스턴스 (테스트·호환용) */
    public static StockAliases none() {
        StockAliases a = new StockAliases();
        a.setEnabled(false);
        return a;
    }

    /** 공식 종목명을 뺀 별칭 목록. 꺼져 있거나 코드가 없거나 항목이 없으면 빈 목록. */
    public List<String> aliasesFor(String code, String officialName) {
        if (!enabled || code == null || byCode == null) return List.of();
        List<String> raw = byCode.get(code);
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty() || t.equals(officialName) || out.contains(t)) continue;
            out.add(t);
        }
        return List.copyOf(out);
    }
}
