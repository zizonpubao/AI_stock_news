package com.stocknews.dto;

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
}