package com.stocknews.service;

import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockCrawlerService {

    private final StockRepository stockRepository;

    @Transactional
    public List<Stock> crawlAndSaveTop10() {
        log.info("네이버 금융 급상승 종목 크롤링 시작");
        List<Stock> result = new ArrayList<>();

        String[] urlsToTry = {
                "https://finance.naver.com/sise/lastsearch2.naver",
                "https://finance.naver.com/sise/lastsearch2.naver?sosok=0"
        };

        for (String url : urlsToTry) {
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .referrer("https://finance.naver.com")
                        .header("Accept-Language", "ko-KR,ko;q=0.9")
                        .timeout(10000)
                        .get();
                result = parseStocks(doc);
                if (!result.isEmpty()) break;
            } catch (IOException e) {
                log.error("URL 접근 실패 ({}): {}", url, e.getMessage());
            }
        }

        if (result.isEmpty()) return createDummyStocks();

        // 첫 번째 종목만 디버그 로그 출력 후 전체 크롤링
        for (int i = 0; i < result.size(); i++) {
            crawlStockDetail(result.get(i), i == 0); // 첫 종목만 디버그
        }

        return result;
    }

    private List<Stock> parseStocks(Document doc) {
        List<Stock> result = new ArrayList<>();
        stockRepository.deleteAll();

        String[] selectors = {"table.type_5 tbody tr", "table.type2 tbody tr", "table tbody tr"};
        Elements rows = new Elements();
        for (String sel : selectors) {
            rows = doc.select(sel);
            if (!rows.isEmpty()) { log.info("셀렉터: '{}' → {}행", sel, rows.size()); break; }
        }

        int rank = 1;
        for (Element row : rows) {
            if (rank > 10) break;
            Elements cols = row.select("td");
            if (cols.size() < 5) continue;
            Element nameEl = cols.get(1).selectFirst("a");
            if (nameEl == null) continue;
            String name = nameEl.text().trim();
            if (name.isEmpty()) continue;

            // 디버그: 첫 행 컬럼 전체 출력
            if (rank == 1) {
                log.info("=== [컬럼 디버그] 첫 종목({}) 전체 td ===", name);
                for (int ci = 0; ci < cols.size(); ci++) {
                    log.info("  cols[{}] = '{}'", ci, cols.get(ci).text().trim());
                }
            }

            // 컬럼 순서 (로그 확인 결과):
            // [0]순위 [1]종목명 [2]검색비율 [3]현재가 [4]전일비(하락/상승 2,100) [5]등락률(-1.11%) [6]거래량
            String currentPrice = cols.get(3).text().trim();
            String changePrice  = cols.get(4).text().trim(); // "하락 2,100" or "상승 2,100"
            String changeRate   = cols.get(5).text().trim(); // "-1.11%" or "+3.22%"
            String volume       = cols.size() > 6 ? cols.get(6).text().trim() : "-";

            // changePrice에서 방향 텍스트 제거, 숫자만 추출 후 부호 붙이기
            boolean isDown = changePrice.contains("하락") || changeRate.startsWith("-");
            String changePriceNum = changePrice.replaceAll("[^0-9,]", "").trim();
            String changePriceFormatted = changePriceNum.isEmpty() ? "-" : (isDown ? "-" : "+") + changePriceNum;

            // changeRate 부호 정리
            String changeRateFormatted = changeRate;
            if (!changeRate.startsWith("+") && !changeRate.startsWith("-")) {
                changeRateFormatted = (isDown ? "-" : "+") + changeRate.replace("%", "") + "%";
            }

            Stock stock = new Stock();
            stock.setCode(extractCode(nameEl.attr("href")));
            stock.setName(name);
            stock.setCurrentPrice(currentPrice);
            stock.setChangePrice(changePriceFormatted);
            stock.setChangeRate(changeRateFormatted);
            stock.setVolume(volume);
            stock.setRanking(rank);

            stockRepository.save(stock);
            result.add(stock);
            rank++;
        }
        return result;
    }

    private void crawlStockDetail(Stock stock, boolean debug) {
        try {
            String url = "https://finance.naver.com/item/main.naver?code=" + stock.getCode();
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .referrer("https://finance.naver.com")
                    .header("Accept-Language", "ko-KR,ko;q=0.9")
                    .timeout(10000)
                    .get();

            // ── 디버그: 첫 종목에서 52주 관련 td 전체 출력 ──
            if (debug) {
                log.info("=== [디버그] {} 52주 관련 td 탐색 ===", stock.getName());
                Elements allTds = doc.select("td");
                for (int i = 0; i < allTds.size(); i++) {
                    String t = allTds.get(i).text();
                    if (t.contains("52") || t.contains("신고") || t.contains("최고")) {
                        String next = (i+1 < allTds.size()) ? allTds.get(i+1).text() : "없음";
                        log.info("  td[{}] = '{}' | 다음td = '{}'", i, t, next);
                    }
                }
                log.info("=== [디버그] .no_info 테이블 전체 텍스트 ===");
                Elements noInfoTds = doc.select("table.no_info td");
                for (int i = 0; i < noInfoTds.size(); i++) {
                    log.info("  no_info td[{}] = '{}'", i, noInfoTds.get(i).text());
                }
            }

            // ── PER, 추정PER, PBR ──
            parsePerPbr(doc, stock);

            // ── 52주 신고가 (디버그 결과 보고 수정 예정) ──
            parse52WeekHigh(doc, stock);

            stockRepository.save(stock);
            log.info("  상세 완료: {} - 52주고:{}, PER:{}, 추정PER:{}, PBR:{}",
                    stock.getName(), stock.getHigh52Week(),
                    stock.getPer(), stock.getEstimatedPer(), stock.getPbr());

        } catch (Exception e) {
            log.warn("상세 크롤링 실패 ({}): {}", stock.getName(), e.getMessage());
        }
    }

    private void parse52WeekHigh(Document doc, Stock stock) {
        try {
            // 로그 분석 결과: "223,000 l 52,900" 형식으로 하나의 td에 최고가 l 최저가가 담겨있음
            // per_table 안에 위치하므로 전체 td를 순회하며 패턴 탐색
            Elements allTds = doc.select("td");
            for (Element td : allTds) {
                String text = td.text().trim();
                // "숫자,숫자 l 숫자,숫자" 패턴이고 숫자 범위가 주가 범위(1000~10000000)인 경우
                if (text.matches("[\\d,]+ l [\\d,]+")) {
                    String[] parts = text.split(" l ");
                    if (parts.length == 2) {
                        String high = parts[0].replaceAll("[^0-9,]", "").trim();
                        if (!high.isEmpty()) {
                            stock.setHigh52Week(high);
                            log.debug("52주 신고가 파싱 성공: {}", high);
                            return;
                        }
                    }
                }
            }
            log.debug("52주 신고가 패턴 미발견");
        } catch (Exception e) {
            log.debug("52주 파싱 실패: {}", e.getMessage());
        }
    }

    private void parsePerPbr(Document doc, Stock stock) {
        try {
            Elements perTrs = doc.select("table.per_table tr");
            for (Element tr : perTrs) {
                Elements tds = tr.select("td");
                if (tds.isEmpty()) continue;
                String trText = tr.text();

                if (trText.contains("PER")) {
                    for (Element td : tds) {
                        String val = extractValueBefore(td.text().trim(), "배");
                        if (val != null) {
                            if (stock.getPer() == null) stock.setPer(val + "배");
                            else if (stock.getEstimatedPer() == null) stock.setEstimatedPer(val + "배");
                        }
                    }
                }
                if (trText.contains("PBR")) {
                    for (Element td : tds) {
                        String val = extractValueBefore(td.text().trim(), "배");
                        if (val != null && stock.getPbr() == null) stock.setPbr(val + "배");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("PER/PBR 파싱 실패: {}", e.getMessage());
        }
    }

    private String extractValueBefore(String text, String delimiter) {
        if (text == null || text.isEmpty() || text.contains("N/A")) return null;
        int idx = text.indexOf(delimiter);
        if (idx > 0) return text.substring(0, idx).trim();
        return null;
    }

    private String extractCode(String href) {
        if (href != null && href.contains("code="))
            return href.substring(href.indexOf("code=") + 5).split("&")[0];
        return "000000";
    }

    private List<Stock> createDummyStocks() {
        stockRepository.deleteAll();
        String[][] data = {
                {"005930","삼성전자","75,000","+5.63%","+4,000","12,345,678"},
                {"000660","SK하이닉스","135,000","+4.81%","+6,200","8,234,567"},
                {"035420","NAVER","215,000","+3.95%","+8,200","3,456,789"},
                {"035720","카카오","52,000","+3.78%","+1,900","5,678,901"},
                {"068270","셀트리온","178,000","+3.49%","+6,000","2,345,678"},
        };
        List<Stock> stocks = new ArrayList<>();
        for (int i = 0; i < data.length; i++) {
            Stock s = new Stock();
            s.setCode(data[i][0]); s.setName(data[i][1]);
            s.setCurrentPrice(data[i][2]); s.setChangeRate(data[i][3]);
            s.setChangePrice(data[i][4]); s.setVolume(data[i][5]);
            s.setRanking(i + 1);
            stockRepository.save(s); stocks.add(s);
        }
        return stocks;
    }
}