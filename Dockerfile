FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

ADD build/libs/kubewatt.jar kubewatt.jar

EXPOSE 9400

ENTRYPOINT ["java","-jar","kubewatt.jar"]
