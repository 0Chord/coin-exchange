# #138 시험에만 선택한다. backend/기반 이미지를 실행 중 자동 변경하지 않는다.
FROM amazoncorretto:25@sha256:769a8f4f7e400098cf4eea22ed56e5690527346044c17dfcc7f6b4c42e105a78

RUN dnf -q -y --setopt=timeout=10 --setopt=retries=1 install \
        iptables-legacy-1.8.8-3.amzn2023.0.2 \
        iptables-legacy-libs-1.8.8-3.amzn2023.0.2 \
        iptables-libs-1.8.8-3.amzn2023.0.2 \
        iputils-20210202-2.amzn2023.0.4 \
    && test "$(/usr/sbin/iptables-legacy --version)" = "iptables v1.8.8 (legacy)" \
    && dnf clean all

WORKDIR /app
COPY classes/ classes/
COPY test-classes/ test-classes/
COPY lib/ lib/

ENTRYPOINT ["java", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED", "-cp", "/app/test-classes:/app/classes:/app/lib/*"]
CMD ["com.exchange.core.api.trading.infrastructure.aeron.MultiMatchingVerificationToolKt", "node"]
