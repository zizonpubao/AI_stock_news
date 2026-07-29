FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew
RUN ./gradlew bootJar --no-daemon -x test
RUN ls -la build/libs/
RUN mv build/libs/$(ls build/libs/ | grep -v plain | head -1) build/libs/app.jar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/app.jar app.jar
RUN ls -la /app/
EXPOSE 8080
# user.timezone=Asia/Seoul: UTC 서버(EC2)에서도 LocalDateTime.now() 등 앱 전체가 한국시간 기준으로 동작
# → updatedAt 표시가 KST 로 맞고, 스케줄러/아카이브 시각도 일관됨
ENTRYPOINT ["java", "-Duser.timezone=Asia/Seoul", "-Dspring.profiles.active=prod", "-jar", "/app/app.jar"]