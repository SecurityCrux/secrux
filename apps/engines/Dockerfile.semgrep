# syntax=docker/dockerfile:1.6

ARG SEMGREP_IMAGE=semgrep/semgrep:latest
FROM ${SEMGREP_IMAGE}

LABEL org.opencontainers.image.title="secrux-semgrep-engine" \
      org.opencontainers.image.description="Semgrep engine container for Secrux" \
      org.opencontainers.image.source="https://github.com/semgrep/semgrep" \
      org.opencontainers.image.licenses="LGPL-2.1-or-later"

ENV SEMGREP_FORCE_COLOR=1 \
    SEMGREP_DEFAULT_COMMAND=ci \
    SEMGREP_ENABLE_SARIF=false

WORKDIR /src

COPY run-semgrep.sh /usr/local/bin/run-semgrep
RUN chmod +x /usr/local/bin/run-semgrep

ENTRYPOINT ["/usr/local/bin/run-semgrep"]
CMD []

