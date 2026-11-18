FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew bootJar --no-daemon -x test
RUN ls -la build/libs/
RUN mv build/libs/$(ls build/libs/ | grep -v plain | head -1) build/libs/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
# 비 root 사용자로 실행 (컨테이너 탈취 시 피해 범위 축소)
RUN groupadd --system app && useradd --system --gid app --no-create-home --shell /usr/sbin/nologin app
COPY --from=build --chown=app:app /app/build/libs/app.jar app.jar
USER app
EXPOSE 8080
# user.timezone=Asia/Seoul: UTC 서버에서도 LocalDateTime.now() 등 앱 전체가 한국시간 기준으로 동작
# MaxRAMPercentage=60: 힙을 컨테이너 메모리 한도(compose mem_limit)의 60% 로 제한 (나머지는 메타스페이스·스레드 스택 등)
# UseSerialGC: 1 vCPU 소형 서버에서 GC 스레드·메모리 오버헤드 최소화
# ExitOnOutOfMemoryError: OOM 이면 프로세스를 종료해 compose 의 restart 정책이 재기동하게 함
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-Dspring.profiles.active=prod", "-XX:MaxRAMPercentage=60", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
