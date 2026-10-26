FROM eclipse-temurin:8-jre-jammy

WORKDIR /app

ENV TZ=Asia/Shanghai \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=18080 \
    SPRING_PROFILES_ACTIVE=druid \
    RUOYI_PROFILE=/data/uploads \
    RUOYI_LOG_PATH=/data/logs \
    WEBUI_FILEANALYSIS_STORAGEPATH=/data/ai-files \
    LOGGING_LEVEL_COM_RUOYI=info \
    SWAGGER_ENABLED=false

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home app \
    && mkdir -p /data/uploads /data/logs /data/ai-files \
    && chown -R 10001:10001 /app /data

COPY --chown=10001:10001 ruoyi-admin.jar /app/ruoyi-admin.jar

USER 10001:10001
EXPOSE 18080

ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "/app/ruoyi-admin.jar"]
