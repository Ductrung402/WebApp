FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw ./mvnw
COPY src ./src
RUN sh ./mvnw --batch-mode --no-transfer-progress clean package

FROM tomcat:9.0-jdk17-temurin
COPY --from=build /build/target/ROOT.war /usr/local/tomcat/webapps/ROOT.war
ENV SOCKET_HOST=0.0.0.0 SOCKET_PORT=8001 RUNTIME_DIRECTORY=/var/lib/ezam/runtime
RUN mkdir -p /var/lib/ezam/runtime
EXPOSE 8080 8001
CMD ["catalina.sh", "run"]
