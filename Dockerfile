ARG TEMURIN_JDK_TAG=17
FROM docker.io/library/eclipse-temurin:${TEMURIN_JDK_TAG} AS builder

ARG SBT_VERSION=1.9.9

# This is based on mozilla docker-sbt https://github.com/mozilla/docker-sbt/blob/main/Dockerfile
# Install sbt
RUN \
  curl -L -o sbt-$SBT_VERSION.deb https://repo.scala-sbt.org/scalasbt/debian/sbt-$SBT_VERSION.deb && \
  dpkg -i sbt-$SBT_VERSION.deb && \
  rm sbt-$SBT_VERSION.deb && \
  apt-get update && \
  apt-get install --no-install-recommends -y git sbt unzip

COPY . /maproulette-api

WORKDIR /maproulette-api
RUN \
    sbt evicted && \
    sbt clean compile dist && \
    unzip -d / target/universal/MapRouletteAPI.zip

FROM docker.io/library/eclipse-temurin:${TEMURIN_JDK_TAG}

# Runtime image needs to have the most up-to-date patches
RUN \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y && \
    rm -rf /var/lib/apt/lists/*
RUN \
    groupadd -g 1001 maproulette && \
    useradd --uid 1001 --gid 1001 --groups 0 --create-home --home-dir /MapRouletteAPI maproulette && \
    chmod 0775 /MapRouletteAPI && \
    chown -R 1001:0 /MapRouletteAPI

COPY --from=builder --chown=1001:0 /MapRouletteAPI /MapRouletteAPI
USER maproulette
WORKDIR /MapRouletteAPI

CMD /MapRouletteAPI/bin/maprouletteapi -Dhttp.port=80
