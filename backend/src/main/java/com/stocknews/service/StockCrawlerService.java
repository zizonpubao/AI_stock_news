package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 네이버 증권 검색 상위(인기검색) 종목 TOP 10 수집.
 *
 * 수집 방식 변경 이력:
 *  - 이전: finance.naver.com 의 인기검색 HTML 표를 Jsoup 으로 파싱.
 *  - 현재: 해당 페이지가 새 증권 사이트로 리다이렉트되고 화면을 스크립트로 그리게 바뀌어 HTML 표가 사라짐.
 *          같은 데이터를 내려주는 모바일 증권의 JSON 응답을 읽는 방식으로 교체.
 *
 * 주의: 이 주소들은 네이버가 공식 문서로 제공하는 Open API 가 아니다.
 *       응답 구조가 예고 없이 바뀔 수 있으므로, 구조가 달라지면 빈 목록을 돌려주고(가짜 데이터 생성 없음)
 *       호출 측에서 "수집 실패"로 기록·표시한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockCrawlerService {

    private static final String SEARCH_TOP_URL =
            "https://m.stock.naver.com/api/stocks/searchTop/all?page=1&pageSize=10";
    private static final String DETAIL_URL = "https://m.stock.naver.com/api/stock/%s/integration";
    private static final int TOP_N = 10;

    // compareToPreviousPrice.code — MarketPriceService 와 같은 규칙
    private static final Set<String> UP = Set.of("1", "2");   // 상한/상승
    private static final Set<String> DOWN = Set.of("4", "5"); // 하한/하락

    private final StockRepository stockRepository;
    private final MarketPriceService marketPriceService;
    private final ObjectMapper objectMapper;

    /**
     * 검색 상위 종목 TOP 10 을 수집해 저장한다.
     *
     * 수집에 실패하면 빈 리스트를 돌려주고 DB 는 건드리지 않는다.
     * (임의의 예시 종목을 만들어 넣지 않는다. 화면에는 직전에 수집된 실제 데이터가 그대로 남는다.)
     */
    @Transactional
    public List<Stock> crawlAndSaveTop10() {
        log.info("네이버 증권 검색 상위 종목 수집 시작");

        List<Stock> result;
        try {
            result = parseSearchTop(fetchJson(SEARCH_TOP_URL));
        } catch (IOException e) {
            log.error("검색 상위 목록 조회 실패: {}", e.getMessage());
            return List.of();
        }

        if (result.isEmpty()) {
            log.error("검색 상위 종목을 한 건도 파싱하지 못함 - 기존 데이터 유지");
            return List.of();
        }

        // 종목별 상세 지표(52주 최고, PER, 추정PER, PBR). 실패해도 해당 종목의 지표만 비워 둔다.
        for (Stock stock : result) {
            try {
                applyDetail(stock, fetchJson(String.format(DETAIL_URL, stock.getCode())));
            } catch (IOException e) {
                log.warn("상세 지표 조회 실패 ({}): {}", stock.getName(), e.getMessage());
            }
        }

        // 새 목록을 확보한 뒤에만 기존 목록을 교체한다 (삭제와 저장이 같은 트랜잭션)
        stockRepository.deleteAll();
        stockRepository.saveAll(result);

        // 실시간 시세로 가격/거래대금 보강 (프리장·애프터마켓 반영)
        enrichRealtimePrices(result);

        log.info("검색 상위 종목 {}건 수집 완료", result.size());
        return result;
    }

    private String fetchJson(String url) throws IOException {
        return Jsoup.connect(url)
                .ignoreContentType(true)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .referrer("https://m.stock.naver.com/")
                .header("Accept-Language", "ko-KR,ko;q=0.9")
                .timeout(8000)
                .execute().body();
    }

    /**
     * 검색 상위 JSON → 종목 목록(최대 10개). 네트워크·DB 에 의존하지 않는 순수 파싱이라 단위 테스트로 검증한다.
     * 응답이 JSON 이 아니거나 구조가 다르면 빈 목록을 돌려준다.
     */
    List<Stock> parseSearchTop(String json) {
        List<Stock> result = new ArrayList<>();
        JsonNode items;
        try {
            items = objectMapper.readTree(json).path("stocks");
        } catch (Exception e) {
            log.error("검색 상위 응답이 JSON 이 아님: {}", e.getMessage());
            return result;
        }
        if (!items.isArray()) return result;

        int rank = 1;
        for (JsonNode item : items) {
            if (rank > TOP_N) break;
            String code = item.path("itemCode").asText("").trim();
            String name = item.path("stockName").asText("").trim();
            String price = item.path("closePrice").asText("").trim();
            if (code.isEmpty() || name.isEmpty() || price.isEmpty()) continue; // 필수값이 없는 항목은 건너뜀

            String dir = item.path("compareToPreviousPrice").path("code").asText("");
            String volume = item.path("accumulatedTradingVolume").asText("-");

            Stock stock = new Stock();
            stock.setCode(code);
            stock.setName(name);
            stock.setCurrentPrice(price);
            stock.setChangePrice(signed(item.path("compareToPreviousClosePrice").asText(""), dir));
            stock.setChangeRate(ratePct(item.path("fluctuationsRatio").asText(""), dir));
            stock.setVolume(volume);
            long valueRaw = item.path("accumulatedTradingValueRaw").asLong(0);
            stock.setTradingValue(valueRaw > 0 ? Long.valueOf(valueRaw) : computeTradingValue(price, volume));
            stock.setRanking(rank);

            result.add(stock);
            rank++;
        }
        return result;
    }

    /**
     * 종목 상세 JSON 의 totalInfos 에서 52주 최고가·PER·추정PER·PBR 을 채운다. (순수 파싱)
     * 값이 없거나 "N/A" 이면 해당 항목은 비워 둔다.
     */
    void applyDetail(Stock stock, String json) {
        JsonNode infos;
        try {
            infos = objectMapper.readTree(json).path("totalInfos");
        } catch (Exception e) {
            log.warn("상세 응답이 JSON 이 아님 ({}): {}", stock.getName(), e.getMessage());
            return;
        }
        if (!infos.isArray()) return;

        for (JsonNode info : infos) {
            String value = cleanValue(info.path("value").asText(""));
            if (value == null) continue;
            switch (info.path("code").asText("")) {
                case "highPriceOf52Weeks" -> stock.setHigh52Week(value);
                case "per" -> stock.setPer(value);
                case "cnsPer" -> stock.setEstimatedPer(value);
                case "pbr" -> stock.setPbr(value);
                default -> { }
            }
        }
    }

    private static String cleanValue(String value) {
        String v = value.trim();
        return (v.isEmpty() || v.contains("N/A") || v.equals("-")) ? null : v;
    }

    /**
     * 목록 응답은 정규장 가격 기준이므로, 실시간 API로 프리장/애프터마켓 가격과
     * 통합 거래대금을 덮어쓴다. 조회 실패 종목은 목록 응답의 값 유지.
     */
    private void enrichRealtimePrices(List<Stock> stocks) {
        List<String> codes = stocks.stream().map(Stock::getCode).toList();
        var priceMap = marketPriceService.fetchPrices(codes);
        for (Stock s : stocks) {
            var p = priceMap.get(s.getCode());
            if (p == null) {
                if (s.getTradingValue() == null) s.setTradingValue(computeTradingValue(s.getCurrentPrice(), s.getVolume()));
                if (s.getMarketSession() == null) s.setMarketSession("REGULAR");
                continue;
            }
            if (p.price() != null && !p.price().isBlank()) s.setCurrentPrice(p.price());
            if (p.changePrice() != null) s.setChangePrice(p.changePrice());
            if (p.changeRate() != null) s.setChangeRate(p.changeRate());
            s.setMarketSession(p.session());
            s.setTradingValue(p.tradingValue() != null
                    ? p.tradingValue()
                    : computeTradingValue(s.getCurrentPrice(), s.getVolume()));
            stockRepository.save(s);
        }
    }

    /** "-7,000" + 방향코드 → "-7,000" / "+7,000" / "0" */
    private static String signed(String num, String dirCode) {
        String n = num.replaceAll("[^0-9,]", "");
        if (n.isEmpty()) return "-";
        if (DOWN.contains(dirCode)) return "-" + n;
        if (UP.contains(dirCode)) return "+" + n;
        return n;
    }

    /** "-3.56" + 방향코드 → "-3.56%" / "+3.56%" / "0.00%" */
    private static String ratePct(String num, String dirCode) {
        String n = num.replaceAll("[^0-9.]", "");
        if (n.isEmpty()) return "-";
        if (DOWN.contains(dirCode)) return "-" + n + "%";
        if (UP.contains(dirCode)) return "+" + n + "%";
        return n + "%";
    }

    /** "1,550,000" × "7,376,718" → 거래대금(원). 파싱 실패 시 null. */
    private static Long computeTradingValue(String price, String volume) {
        try {
            long p = Long.parseLong(price.replaceAll("[^0-9]", ""));
            long v = Long.parseLong(volume.replaceAll("[^0-9]", ""));
            if (p <= 0 || v <= 0) return null;
            return p * v;
        } catch (Exception e) {
            return null;
        }
    }
}
