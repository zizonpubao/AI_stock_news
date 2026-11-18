package com.stocknews.repository;

import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.NewsStatus;
import com.stocknews.entity.Stock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 내장 H2 로 실제 SQL 을 실행해 검증한다. */
@DataJpaTest
class StockRepositoryTest {

    @Autowired StockRepository stockRepository;
    @Autowired NewsRepository newsRepository;
    @Autowired TestEntityManager em;

    private Stock savedStockWithNews() {
        Stock stock = new Stock();
        stock.setCode("111111");
        stock.setName("가나전자");
        stock.setRanking(1);
        stock = stockRepository.saveAndFlush(stock);

        NewsArticle news = new NewsArticle();
        news.setStock(stock);
        news.setTitle("가나전자 관련 기사");
        news.setLink("https://example.com/1");
        newsRepository.saveAndFlush(news);
        em.clear();
        return stock;
    }

    @Test
    @DisplayName("새 종목의 분석 상태 기본값은 PENDING")
    void defaultStatusIsPending() {
        Stock stock = savedStockWithNews();

        Stock found = stockRepository.findById(stock.getId()).orElseThrow();

        assertThat(found.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(found.getAiAnalysis()).isNull();
    }

    @Test
    @DisplayName("분석 저장은 분석 컬럼만 바꾸고 수집된 뉴스는 그대로 둔다")
    void updateAnalysisKeepsNews() {
        Stock stock = savedStockWithNews();

        LocalDateTime at = LocalDateTime.of(2026, 11, 18, 10, 0);

        int updated = stockRepository.updateAnalysis(stock.getId(), "📈 주요 이슈\n- A", AnalysisStatus.SUCCESS,
                "gemini-2.5-flash", at, "POSITIVE", null);

        Stock found = stockRepository.findById(stock.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(found.getAiAnalysis()).isEqualTo("📈 주요 이슈\n- A");
        assertThat(found.getAnalysisStatus()).isEqualTo(AnalysisStatus.SUCCESS);
        assertThat(found.getAnalysisModel()).isEqualTo("gemini-2.5-flash");
        assertThat(found.getAnalyzedAt()).isEqualTo(at);
        assertThat(found.getAnalysisSentiment()).isEqualTo("POSITIVE");
        assertThat(found.getAnalysisFailReason()).isNull();
        assertThat(newsRepository.findByStockOrderByCollectedAtDesc(found)).hasSize(1);
    }

    @Test
    @DisplayName("뉴스는 저장 순서(id 오름차순)로 조회된다")
    void newsAreReturnedInInsertionOrder() {
        Stock stock = savedStockWithNews(); // 뉴스 1건 저장됨
        Stock found = stockRepository.findById(stock.getId()).orElseThrow();
        for (String title : new String[]{"둘째", "셋째"}) {
            NewsArticle n = new NewsArticle();
            n.setStock(found);
            n.setTitle(title);
            newsRepository.saveAndFlush(n);
        }
        em.clear();

        var list = newsRepository.findByStockOrderByIdAsc(stockRepository.findById(stock.getId()).orElseThrow());

        assertThat(list).extracting(NewsArticle::getTitle).containsExactly("가나전자 관련 기사", "둘째", "셋째");
    }

    @Test
    @DisplayName("새 종목의 뉴스 상태 기본값은 PENDING")
    void defaultNewsStatusIsPending() {
        Stock stock = savedStockWithNews();

        Stock found = stockRepository.findById(stock.getId()).orElseThrow();

        assertThat(found.getNewsStatus()).isEqualTo(NewsStatus.PENDING);
    }

    @Test
    @DisplayName("뉴스 상태 저장은 상태만 바꾸고 수집된 뉴스와 분석 상태는 그대로 둔다")
    void updateNewsStatusKeepsNews() {
        Stock stock = savedStockWithNews();

        int updated = stockRepository.updateNewsStatus(stock.getId(), NewsStatus.FAILED);

        Stock found = stockRepository.findById(stock.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(found.getNewsStatus()).isEqualTo(NewsStatus.FAILED);
        assertThat(found.getAnalysisStatus()).isEqualTo(AnalysisStatus.PENDING);
        assertThat(newsRepository.findByStockOrderByCollectedAtDesc(found)).hasSize(1);
    }

    @Test
    @DisplayName("분석 실패는 분석 내용 없이 FAILED 로 저장된다")
    void failedAnalysisStoresNoText() {
        Stock stock = savedStockWithNews();

        stockRepository.updateAnalysis(stock.getId(), null, AnalysisStatus.FAILED, null, null, null, "NO_NEWS");

        Stock found = stockRepository.findById(stock.getId()).orElseThrow();
        assertThat(found.getAiAnalysis()).isNull();
        assertThat(found.getAnalysisFailReason()).isEqualTo("NO_NEWS");
        assertThat(found.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(newsRepository.findByStockOrderByCollectedAtDesc(found)).hasSize(1);
    }
}
