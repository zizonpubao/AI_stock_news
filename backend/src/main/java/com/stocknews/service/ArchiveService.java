package com.stocknews.service;

import com.stocknews.dto.NewsDto;
import com.stocknews.dto.StockDto;
import com.stocknews.entity.DailyArchiveNews;
import com.stocknews.entity.DailyArchiveStock;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.DailyArchiveNewsRepository;
import com.stocknews.repository.DailyArchiveStockRepository;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 매 거래일 종가 시점의 TOP10 종목 + 뉴스 + AI분석을 날짜별로 아카이브(append-only).
 * 현재 화면용 stocks/news_articles 는 매시간 덮어써지므로, 과거 조회를 위해 별도 보관한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArchiveService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StockRepository stockRepository;
    private final NewsRepository newsRepository;
    private final DailyArchiveStockRepository archiveStockRepository;
    private final DailyArchiveNewsRepository archiveNewsRepository;

    /** 오늘(KST) 날짜로 현재 라이브 데이터를 아카이브. 같은 날 재실행 시 덮어씀(멱등). */
    @Transactional
    public int archiveToday() {
        LocalDate today = LocalDate.now(KST);
        // 멱등: 기존 오늘 아카이브 제거 (엔티티 삭제 → 뉴스도 cascade 삭제)
        List<DailyArchiveStock> existing = archiveStockRepository.findBySnapshotDateOrderByRankingAsc(today);
        if (!existing.isEmpty()) {
            archiveStockRepository.deleteAll(existing);
            archiveStockRepository.flush();
        }

        List<Stock> stocks = stockRepository.findAllByOrderByRankingAsc();
        int saved = 0;
        for (Stock s : stocks) {
            DailyArchiveStock a = new DailyArchiveStock();
            a.setSnapshotDate(today);
            a.setCode(s.getCode());
            a.setName(s.getName());
            a.setRanking(s.getRanking());
            a.setCurrentPrice(s.getCurrentPrice());
            a.setChangeRate(s.getChangeRate());
            a.setChangePrice(s.getChangePrice());
            a.setVolume(s.getVolume());
            a.setTradingValue(s.getTradingValue());
            a.setHigh52Week(s.getHigh52Week());
            a.setPer(s.getPer());
            a.setEstimatedPer(s.getEstimatedPer());
            a.setPbr(s.getPbr());
            a.setAiAnalysis(s.getAiAnalysis());

            for (NewsArticle n : newsRepository.findByStockOrderByCollectedAtDesc(s)) {
                DailyArchiveNews an = new DailyArchiveNews();
                an.setArchiveStock(a);
                an.setTitle(n.getTitle());
                an.setDescription(n.getDescription());
                an.setLink(n.getLink());
                an.setPubDate(n.getPubDate());
                a.getNews().add(an);
            }
            archiveStockRepository.save(a);
            saved++;
        }
        log.info("[아카이브] {} 저장 완료 - {}개 종목", today, saved);
        return saved;
    }

    /** 아카이브가 있는 날짜 목록 (최신순) */
    @Transactional(readOnly = true)
    public List<LocalDate> getDates() {
        return archiveStockRepository.findDistinctDates();
    }

    /** 특정 날짜의 TOP10 (실시간과 동일한 StockDto 형태) */
    @Transactional(readOnly = true)
    public List<StockDto> getStocksByDate(LocalDate date) {
        return archiveStockRepository.findBySnapshotDateOrderByRankingAsc(date)
                .stream().map(StockDto::from).collect(Collectors.toList());
    }

    /** 아카이브 종목 1건 + 뉴스 ({stock, news}) */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getStockWithNews(Long archiveStockId) {
        return archiveStockRepository.findById(archiveStockId).map(a -> {
            List<NewsDto> news = archiveNewsRepository.findByArchiveStockOrderByIdAsc(a)
                    .stream().map(NewsDto::from).collect(Collectors.toList());
            return Map.of("stock", StockDto.from(a), "news", news);
        });
    }
}
