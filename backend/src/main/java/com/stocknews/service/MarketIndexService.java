package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.dto.MarketIndexDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 코스피/코스닥 지수 조회. 네이버 실시간 polling JSON API 사용.
 * 매 요청마다 외부 호출하지 않도록 30초 캐시.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketIndexService {

    private final ObjectMapper objectMapper;

    private static final String URL =
            "https://polling.finance.naver.com/api/realtime/domestic/index/KOSPI,KOSDAQ";
    private static final long CACHE_TTL_MS = 30_000;

    private volatile List<MarketIndexDto> cache = null;
    private volatile long cachedAt = 0;

    public List<MarketIndexDto> getIndices() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cachedAt < CACHE_TTL_MS) {
            return cache;
        }
        try {
            String json = Jsoup.connect(URL)
                    .ignoreContentType(true)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com/")
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .timeout(5000)
                    .execute().body();

            JsonNode datas = objectMapper.readTree(json).get("datas");
            List<MarketIndexDto> result = new ArrayList<>();
            if (datas != null && datas.isArray()) {
                for (JsonNode d : datas) {
                    String code = d.path("itemCode").asText();          // KOSPI / KOSDAQ
                    String name = "KOSDAQ".equals(code) ? "코스닥" : "코스피";
                    String value = d.path("closePrice").asText();
                    String change = d.path("compareToPreviousClosePrice").asText();
                    String ratio = d.path("fluctuationsRatio").asText();
                    // 방향 코드: 1 상한, 2 상승 → up / 3 보합 / 4 하한, 5 하락 → down
                    String dirCode = d.path("compareToPreviousPrice").path("code").asText();
                    boolean up = "1".equals(dirCode) || "2".equals(dirCode);
                    result.add(new MarketIndexDto(name, value, change, ratio + "%", up));
                }
            }
            if (!result.isEmpty()) {
                cache = result;
                cachedAt = now;
            }
            return result.isEmpty() && cache != null ? cache : result;
        } catch (Exception e) {
            log.warn("지수 조회 실패: {}", e.getMessage());
            return cache != null ? cache : List.of();  // 실패 시 마지막 캐시 또는 빈 목록
        }
    }
}
