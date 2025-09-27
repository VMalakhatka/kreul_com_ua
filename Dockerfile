# ===== Сборка =====
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# ===== Рантайм =====
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Устанавливаем wget для healthcheck (в Debian/Jammy он доступен)
RUN apt-get update && apt-get install -y wget && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

# ENV переменные можно переопределять при запуске docker-compose
ENV SERVER_PORT=8080

EXPOSE 8080

# Healthcheck: пока нет actuator, проверяем просто корень /
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]