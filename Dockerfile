FROM maven:3-eclipse-temurin-25 as builder
LABEL maintainer="contact@bittich.be"

WORKDIR /app

COPY pom.xml .

RUN mvn verify --fail-never

COPY ./src ./src

RUN mvn package -DskipTests

FROM ibm-semeru-runtimes:open-25-jre

WORKDIR /app

COPY --from=builder /app/target/app.jar ./app.jar

ENTRYPOINT [ "java", "-Xtune:virtualized","-XX:+UseCompactObjectHeaders", "-Xshareclasses:cacheDir=/opt/shareclasses", "-jar","/app/app.jar"]
