FROM maven:3.9.16-eclipse-temurin-26-noble AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
COPY models/animals.json.example ./models/animals.json.example
RUN mvn -B -ntp verify

FROM eclipse-temurin:26-jdk-noble

RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
        xvfb x11vnc util-linux fluxbox fonts-dejavu-core x11-utils xterm \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*-boot.jar /app/nexus-core.jar

EXPOSE 8080 5900

COPY entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

ENTRYPOINT ["/app/entrypoint.sh"]