package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 검색 상위 JSON 파싱 검증. 실제 응답과 같은 필드 구조를 쓰되 종목은 가상의 값이다.
 * (네트워크를 타지 않으므로 "지금 네이버가 응답하는지"는 이 테스트로 알 수 없다.)
 */
class StockCrawlerServiceTest {

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final MarketPriceService marketPriceService = mock(MarketPriceService.class);
    private final StockCrawlerService service =
            new StockCrawlerService(stockRepository, marketPriceService, new ObjectMapper());

    /** dirCode: 2=상승, 5=하락, 3=보합 */
    private static String item(String code, String name, String price, String change, String dirCode,
                               String ratio, String volume, String valueRaw) {
        return "{\"itemCode\":\"" + code + "\",\"stockName\":\"" + name + "\",\"closePrice\":\"" + price + "\","
                + "\"compareToPreviousClosePrice\":\"" + change + "\","
                + "\"compareToPreviousPrice\":{\"code\":\"" + dirCode + "\"},"
                + "\"fluctuationsRatio\":\"" + ratio + "\","
                + "\"accumulatedTradingVolume\":\"" + volume + "\","
                + "\"accumulatedTradingValueRaw\":\"" + valueRaw + "\"}";
    }

    private static String response(String... items) {
        return "{\"stockListSortType\":\"SEARCH_TOP\",\"stocks\":[" + String.join(",", items) + "]}";
    }

    @Test
    @DisplayName("목록 JSON 을 종목으로 파싱하고 방향 코드에 따라 부호를 붙인다")
    void parsesItems() {
        String json = response(
                item("111111", "가나전자", "70,000", "1,000", "2", "1.45", "1,000", "70000000"),
                item("222222", "다라화학", "50,000", "-2,100", "5", "-4.03", "2,000", "100000000"),
                item("333333", "마바건설", "10,000", "0", "3", "0.00", "500", "5000000"));

        List<Stock> stocks = service.parseSearchTop(json);

        assertThat(stocks).hasSize(3);

        Stock up = stocks.get(0);
        assertThat(up.getCode()).isEqualTo("111111");
        assertThat(up.getName()).isEqualTo("가나전자");
        assertThat(up.getRanking()).isEqualTo(1);
        assertThat(up.getCurrentPrice()).isEqualTo("70,000");
        assertThat(up.getChangePrice()).isEqualTo("+1,000");
        assertThat(up.getChangeRate()).isEqualTo("+1.45%");
        assertThat(up.getVolume()).isEqualTo("1,000");
        assertThat(up.getTradingValue()).isEqualTo(70_000_000L);

        Stock down = stocks.get(1);
        assertThat(down.getRanking()).isEqualTo(2);
        assertThat(down.getChangePrice()).isEqualTo("-2,100");
        assertThat(down.getChangeRate()).isEqualTo("-4.03%");

        Stock flat = stocks.get(2);
        assertThat(flat.getChangePrice()).isEqualTo("0");
        assertThat(flat.getChangeRate()).isEqualTo("0.00%");
    }

    @Test
    @DisplayName("거래대금 원값이 없으면 현재가 × 거래량으로 계산한다")
    void computesTradingValueWhenRawMissing() {
        String json = response(item("111111", "가나전자", "70,000", "1,000", "2", "1.45", "1,000", ""));

        assertThat(service.parseSearchTop(json).get(0).getTradingValue()).isEqualTo(70_000L * 1_000L);
    }

    @Test
    @DisplayName("항목이 10개를 넘어도 상위 10개만 가져온다")
    void takesTop10Only() {
        String[] items = new String[15];
        for (int i = 0; i < 15; i++) {
            items[i] = item(String.format("%06d", i + 1), "종목" + (i + 1), "1,000", "10", "2", "1.00", "100", "100000");
        }

        List<Stock> stocks = service.parseSearchTop(response(items));

        assertThat(stocks).hasSize(10);
        assertThat(stocks.get(9).getRanking()).isEqualTo(10);
    }

    @Test
    @DisplayName("필수값(코드·이름·가격)이 빠진 항목은 건너뛰고 순위는 이어서 매긴다")
    void skipsItemsMissingRequiredFields() {
        String json = response(
                item("", "이름만", "1,000", "10", "2", "1.00", "100", "100000"),
                item("222222", "다라화학", "50,000", "-2,100", "5", "-4.03", "2,000", "100000000"));

        List<Stock> stocks = service.parseSearchTop(json);

        assertThat(stocks).hasSize(1);
        assertThat(stocks.get(0).getCode()).isEqualTo("222222");
        assertThat(stocks.get(0).getRanking()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답 구조가 바뀌었거나 JSON 이 아니면 빈 목록을 돌려주고 임의 종목을 만들지 않는다")
    void unexpectedResponseYieldsNothing() {
        assertThat(service.parseSearchTop("{\"items\":[]}")).isEmpty();
        assertThat(service.parseSearchTop("{\"stocks\":\"none\"}")).isEmpty();
        assertThat(service.parseSearchTop("<html><body><div id=\"root\"></div></body></html>")).isEmpty();
        assertThat(service.parseSearchTop("")).isEmpty();
    }

    @Test
    @DisplayName("상세 JSON 에서 52주 최고가·PER·추정PER·PBR 을 채운다")
    void appliesDetail() {
        Stock stock = new Stock();
        stock.setName("가나전자");
        String json = "{\"totalInfos\":["
                + "{\"code\":\"highPriceOf52Weeks\",\"key\":\"52주 최고\",\"value\":\"90,000\"},"
                + "{\"code\":\"per\",\"key\":\"PER\",\"value\":\"8.51배\"},"
                + "{\"code\":\"cnsPer\",\"key\":\"추정PER\",\"value\":\"4.16배\"},"
                + "{\"code\":\"pbr\",\"key\":\"PBR\",\"value\":\"2.21배\"},"
                + "{\"code\":\"eps\",\"key\":\"EPS\",\"value\":\"1,000원\"}]}";

        service.applyDetail(stock, json);

        assertThat(stock.getHigh52Week()).isEqualTo("90,000");
        assertThat(stock.getPer()).isEqualTo("8.51배");
        assertThat(stock.getEstimatedPer()).isEqualTo("4.16배");
        assertThat(stock.getPbr()).isEqualTo("2.21배");
    }

    @Test
    @DisplayName("상세 값이 N/A 이거나 응답이 비정상이면 지표를 비워 두고 예외를 내지 않는다")
    void detailWithMissingValues() {
        Stock stock = new Stock();
        stock.setName("가나전자");

        service.applyDetail(stock, "{\"totalInfos\":[{\"code\":\"per\",\"value\":\"N/A\"},{\"code\":\"pbr\",\"value\":\"\"}]}");
        service.applyDetail(stock, "{}");
        service.applyDetail(stock, "not json");

        assertThat(stock.getPer()).isNull();
        assertThat(stock.getPbr()).isNull();
        assertThat(stock.getHigh52Week()).isNull();
    }

    @Test
    @DisplayName("파싱은 DB 를 건드리지 않는다 (수집 실패 시 기존 데이터가 지워지지 않도록)")
    void parsingDoesNotTouchDatabase() {
        service.parseSearchTop(response(item("111111", "가나전자", "70,000", "1,000", "2", "1.45", "1,000", "70000000")));
        service.parseSearchTop("garbage");

        verifyNoInteractions(stockRepository);
    }
}
