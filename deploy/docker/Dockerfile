FROM eclipse-temurin:21-jre

WORKDIR /opt/debezium-cdc-rocketmq

RUN mkdir -p /opt/debezium-cdc-rocketmq/{config,logs,offsets}

COPY lib/debezium-cdc-rocketmq.jar /opt/debezium-cdc-rocketmq/app.jar

EXPOSE 8082

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--Xms512m -Xmx2g -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai} -jar /opt/debezium-cdc-rocketmq/app.jar --spring.profiles.active=prod --spring.config.location=file:/opt/debezium-cdc-rocketmq/config/application-prod.yml"]

