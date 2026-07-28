package com.stocknews.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /**
     * 허용할 오리진 패턴 (콤마 구분). 환경변수 CORS_ALLOWED_ORIGINS 로 덮어쓸 수 있음.
     * 기본값: 로컬 + 모든 Vercel 배포 도메인.
     * allowCredentials(true) 와 함께 와일드카드를 쓰려면 allowedOriginPatterns 를 사용해야 한다.
     */
    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:5173,https://*.vercel.app}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
