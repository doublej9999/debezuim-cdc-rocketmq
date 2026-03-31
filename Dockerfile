# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /workspace
COPY pom.xml ./
COPY src ./src

RUN mvn -B -DskipTests package \
    && cp "$(ls target/*.jar | grep -v '\.original\.jar$' | head -n 1)" /workspace/app.jar

FROM eclipse-temurin:21-jre

WORKDIR /app
RUN mkdir -p /app/logs /app/offsets

COPY --from=builder /workspace/app.jar /app/app.jar

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--Xms512m -Xmx2g -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai} -jar /app/app.jar"]
