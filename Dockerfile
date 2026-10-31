# syntax=docker/dockerfile:1

# ---------- Etapa 1: builder ----------
FROM amazoncorretto:21-alpine AS builder
WORKDIR /build

# Maven oficial sobre el JDK de Corretto (el paquete apk de maven trae otro JDK)
ARG MAVEN_VERSION=3.9.11
RUN wget -qO- https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xz -C /opt \
  && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn

# Dependencias primero para aprovechar la caché de capas
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests \
  && cp target/*.jar /build/app.jar

# ---------- Etapa 2: runtime ----------
FROM amazoncorretto:21-alpine
WORKDIR /app

# Ejecutar sin privilegios de root
RUN addgroup -S gateway && adduser -S gateway -G gateway

COPY --from=builder --chown=gateway:gateway /build/app.jar ./app.jar

USER gateway

# La JVM ajusta el heap al límite de memoria del contenedor (Fargate / App Runner)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# Configuración obligatoria por variables de entorno (sin valores por defecto):
#   JWT_SECRET, ESTUDIANTES_URL, MATERIAS_URL, INSCRIPCIONES_URL, AUTENTICACION_URL, FRONTEND_URL
EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:3000/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
