package com.stocknews.dto;

import com.stocknews.entity.DailyArchiveStock;
import com.stocknews.entity.Stock;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
public class StockDto {
    private Long id;
    private String code;
    private String name;
    private String currentPrice;
    private String changeRate;
    private String changePrice;
    private String volume;
    private Long tradingValue;
    private String marketSession;
    private Integer ranking;
    private String high52Week;
    private String per;
    private String estimatedPer;
    private String pbr;
    private String aiAnalysis;
    private String analysisStatus; // PENDING / SUCCESS / FAILED (기존 데이터는 null)
    private String newsStatus;     // PENDING / SUCCESS / FAILED (기존 데이터는 null)
    private String analysisModel;      // 실제로 응답한 모델 (옛 데이터는 null)
    private LocalDateTime analyzedAt;  // 분석 저장 시각 (옛 데이터는 null)
    private String analysisSentiment;  // POSITIVE / NEUTRAL / NEGATIVE (판단 불가·옛 데이터는 null)
    private String analysisFailReason; // NO_NEWS / AI_CALL_FAILED / INVALID_RESPONSE (성공이면 null)
    private LocalDateTime updatedAt;

    public static StockDto from(Stock stock) {
        StockDto dto = new StockDto();
        dto.id = stock.getId();
        dto.code = stock.getCode();
        dto.name = stock.getName();
        dto.currentPrice = stock.getCurrentPrice();
        dto.changeRate = stock.getChangeRate();
        dto.changePrice = stock.getChangePrice();
        dto.volume = stock.getVolume();
        dto.tradingValue = stock.getTradingValue();
        dto.marketSession = stock.getMarketSession();
        dto.ranking = stock.getRanking();
        dto.high52Week = stock.getHigh52Week();
        dto.per = stock.getPer();
        dto.estimatedPer = stock.getEstimatedPer();
        dto.pbr = stock.getPbr();
        dto.aiAnalysis = stock.getAiAnalysis();
        dto.analysisStatus = stock.getAnalysisStatus() != null ? stock.getAnalysisStatus().name() : null;
        dto.newsStatus = stock.getNewsStatus() != null ? stock.getNewsStatus().name() : null;
        dto.analysisModel = stock.getAnalysisModel();
        dto.analyzedAt = stock.getAnalyzedAt();
        dto.analysisSentiment = stock.getAnalysisSentiment();
        dto.analysisFailReason = stock.getAnalysisFailReason();
        dto.updatedAt = stock.getUpdatedAt();
        return dto;
    }

    /** 아카이브 종목 → 동일한 StockDto 형태 (프론트가 실시간과 같은 카드로 렌더) */
    public static StockDto from(DailyArchiveStock a) {
        StockDto dto = new StockDto();
        dto.id = a.getId();
        dto.code = a.getCode();
        dto.name = a.getName();
        dto.currentPrice = a.getCurrentPrice();
        dto.changeRate = a.getChangeRate();
        dto.changePrice = a.getChangePrice();
        dto.volume = a.getVolume();
        dto.tradingValue = a.getTradingValue();
        dto.ranking = a.getRanking();
        dto.high52Week = a.getHigh52Week();
        dto.per = a.getPer();
        dto.estimatedPer = a.getEstimatedPer();
        dto.pbr = a.getPbr();
        dto.aiAnalysis = a.getAiAnalysis();
        dto.analysisStatus = a.getAnalysisStatus() != null ? a.getAnalysisStatus().name() : null;
        dto.newsStatus = a.getNewsStatus() != null ? a.getNewsStatus().name() : null;
        dto.analysisModel = a.getAnalysisModel();
        dto.analyzedAt = a.getAnalyzedAt();
        dto.analysisSentiment = a.getAnalysisSentiment();
        dto.analysisFailReason = a.getAnalysisFailReason();
        dto.updatedAt = a.getSnapshotDate() != null ? a.getSnapshotDate().atTime(16, 0) : null;
        return dto;
    }
}