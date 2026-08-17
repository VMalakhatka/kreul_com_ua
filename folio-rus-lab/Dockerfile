FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package

FROM eclipse-temurin:17-jre-jammy

LABEL org.opencontainers.image.title="folio-rus-lab" \
      org.opencontainers.image.description="Local-only laboratory API for the disposable Paint_Rus database copy"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system foliolab \
    && useradd --system --gid foliolab --no-create-home --shell /usr/sbin/nologin foliolab

WORKDIR /app
COPY --from=build --chown=foliolab:foliolab /workspace/target/folio-rus-lab-*.jar /app/app.jar

USER foliolab:foliolab
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
