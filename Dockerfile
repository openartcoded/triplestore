FROM maven:3.8-openjdk-18 as builder
LABEL maintainer="contact@bittich.be"

WORKDIR /app

COPY pom.xml .

RUN mvn verify --fail-never

COPY ./src ./src

RUN mvn package -DskipTests

FROM ibm-semeru-runtimes:open-18-jre

WORKDIR /app

COPY --from=builder /app/target/app.jar ./app.jar

ENTRYPOINT [ "java", "-Xtune:virtualized", "-Xshareclasses:cacheDir=/opt/shareclasses", "-jar","/app/app.jar"]
