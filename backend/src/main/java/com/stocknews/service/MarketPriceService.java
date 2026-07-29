package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 네이버 실시간 시세 API. 정규장(KRX)뿐 아니라 넥스트레이드(NXT) 프리장/애프터마켓 가격까지 제공.
 * lastsearch2(인기검색) 페이지는 정규장 가격만 주므로, 가격/거래대금은 이 API로 보강한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketPriceService {

    private final ObjectMapper objectMapper;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String BASE = "https://polling.finance.naver.com/api/realtime/domestic/stock/";
    private static final Set<String> UP = Set.of("1", "2");   // 상한/상승
    private static final Set<String> DOWN = Set.of("4", "5"); // 하한/하락

    /** 종목 하나의 세션별 가격 정보. session: REGULAR / PRE / AFTER / CLOSED */
    public record PriceInfo(String price, String changePrice, String changeRate,
                            String session, Long tradingValue) {}

    /** 종목코드들의 실시간 가격을 한 번에 조회 (프리장/애프터마켓 반영). */
    public Map<String, PriceInfo> fetchPrices(List<String> codes) {
        Map<String, PriceInfo> out = new HashMap<>();
        if (codes == null || codes.isEmpty()) return out;
        try {
            JsonNode datas = call(String.join(",", codes));
            if (datas != null && datas.isArray()) {
                for (JsonNode d : datas) out.put(d.path("itemCode").asText(), parse(d));
            }
        } catch (Exception e) {
            log.warn("실시간 시세 조회 실패: {}", e.getMessage());
        }
        return out;
    }

    /**
     * 오늘이 거래일인지 판단 (주말·공휴일·임시휴장 자동 감지).
     * 삼성전자(005930)를 기준으로: 현재 정규장/시간외 열려있거나, 오늘 체결 이력이 있으면 거래일.
     * 조회 실패 시엔 막지 않도록 true 반환(fail-open).
     */
    public boolean isTradingToday() {
        try {
            JsonNode datas = call("005930");
            if (datas == null || !datas.isArray() || datas.isEmpty()) return true;
            JsonNode d = datas.get(0);
            if ("OPEN".equals(d.path("marketStatus").asText())) return true;
            if ("OPEN".equals(d.path("overMarketPriceInfo").path("overMarketStatus").asText())) return true;
            String traded = d.path("localTradedAt").asText("");   // "2026-07-29T..."
            if (traded.length() >= 10) {
                return LocalDate.parse(traded.substring(0, 10)).equals(LocalDate.now(KST));
            }
        } catch (Exception e) {
            log.warn("거래일 판단 실패(진행): {}", e.getMessage());
        }
        return true;
    }

    private JsonNode call(String codesCsv) throws Exception {
        String json = Jsoup.connect(BASE + codesCsv)
                .ignoreContentType(true)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .referrer("https://finance.naver.com/")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .timeout(6000)
                .execute().body();
        return objectMapper.readTree(json).get("datas");
    }

    private PriceInfo parse(JsonNode d) {
        JsonNode ov = d.path("overMarketPriceInfo");
        boolean overOpen = ov.isObject() && "OPEN".equals(ov.path("overMarketStatus").asText());

        String price, changePrice, changeRate, session;
        if (overOpen) {
            String type = ov.path("tradingSessionType").asText();     // PRE_MARKET / AFTER_MARKET
            session = type.startsWith("PRE") ? "PRE" : "AFTER";
            String dir = ov.path("compareToPreviousPrice").path("code").asText();
            price = ov.path("overPrice").asText();
            changePrice = signed(ov.path("compareToPreviousClosePrice").asText(), dir);
            changeRate = ratePct(ov.path("fluctuationsRatio").asText(), dir);
        } else {
            session = "OPEN".equals(d.path("marketStatus").asText()) ? "REGULAR" : "CLOSED";
            String dir = d.path("compareToPreviousPrice").path("code").asText();
            price = d.path("closePrice").asText();
            changePrice = signed(d.path("compareToPreviousClosePrice").asText(), dir);
            changeRate = ratePct(d.path("fluctuationsRatio").asText(), dir);
        }
        // 통합(KRX+NXT) 거래대금 — 근사치 대신 실제값
        long tvRaw = d.path("integratedPriceInfo").path("accumulatedTradingValueRaw").asLong(0);
        Long tradingValue = tvRaw > 0 ? tvRaw : null;

        return new PriceInfo(price, changePrice, changeRate, session, tradingValue);
    }

    private String signed(String num, String dirCode) {
        String n = num.replaceAll("[^0-9,]", "");
        if (n.isEmpty()) return "-";
        if (DOWN.contains(dirCode)) return "-" + n;
        if (UP.contains(dirCode)) return "+" + n;
        return n;
    }

    private String ratePct(String num, String dirCode) {
        String n = num.replaceAll("[^0-9.]", "");
        if (n.isEmpty()) return "-";
        if (DOWN.contains(dirCode)) return "-" + n + "%";
        if (UP.contains(dirCode)) return "+" + n + "%";
        return n + "%";
    }
}
