package com.stocknews.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 관리용 API 경로에만 {@link AdminTokenInterceptor} 를 적용한다. 조회용 GET API 는 공개 그대로. */
@Configuration
@RequiredArgsConstructor
public class AdminAuthConfig implements WebMvcConfigurer {

    private final AdminTokenInterceptor adminTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminTokenInterceptor)
                .addPathPatterns("/api/stocks/refresh", "/api/archive/run", "/api/admin/**");
    }
}
