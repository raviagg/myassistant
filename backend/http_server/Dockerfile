FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/scala-3.4.2/myassistant-backend.jar app.jar

EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --retries=5 \
    CMD wget -qO- http://localhost:8080/health || exit 1

ENV JAVA_OPTS="-Xms64m -Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
