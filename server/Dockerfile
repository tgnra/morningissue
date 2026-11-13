# The Morning Issue server. Build from the repository root:
#   docker build -f server/Dockerfile -t morningissue-server .
#   docker run -p 8417:8417 -v morningissue:/data -e MORNINGISSUE_USERS=tania morningissue-server
# The first start logs each new user's password; it is also in /data/initial-passwords/.

FROM docker.io/library/eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . /src
ARG SERVER_VERSION=0.1.0
# -PserverOnly leaves the Android modules out, so no Android SDK is needed.
RUN ./gradlew --no-daemon -PserverOnly -PserverVersion=${SERVER_VERSION} :server:installDist

# glibc, not Alpine: the bundled SQLite's native library needs it.
FROM docker.io/library/eclipse-temurin:21-jre
COPY --from=build /src/server/build/install/morningissue-server /opt/morningissue
RUN mkdir -p /data && chown 10001:10001 /data
ENV MORNINGISSUE_DATA_DIR=/data
VOLUME /data
EXPOSE 8417
USER 10001
ENTRYPOINT ["/opt/morningissue/bin/morningissue-server"]
CMD ["serve"]
