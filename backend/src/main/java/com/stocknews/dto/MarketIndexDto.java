package com.stocknews.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MarketIndexDto {
    private String name;        // 코스피 / 코스닥
    private String value;       // 지수 (예: "6,023.66")
    private String change;      // 전일대비 (예: "-732.09")
    private String changeRate;  // 등락률 (예: "-10.84%")
    private boolean up;         // true=상승, false=하락(보합 포함)
}
