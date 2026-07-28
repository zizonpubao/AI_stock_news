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
    private Integer ranking;
    private String high52Week;
    private String per;
    private String estimatedPer;
    private String pbr;
    private String aiAnalysis;
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
        dto.ranking = stock.getRanking();
        dto.high52Week = stock.getHigh52Week();
        dto.per = stock.getPer();
        dto.estimatedPer = stock.getEstimatedPer();
        dto.pbr = stock.getPbr();
        dto.aiAnalysis = stock.getAiAnalysis();
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
        dto.updatedAt = a.getSnapshotDate() != null ? a.getSnapshotDate().atTime(16, 0) : null;
        return dto;
    }
}