FROM amazoncorretto:25

WORKDIR /app
COPY classes/ classes/
COPY test-classes/ test-classes/
COPY lib/ lib/

ENTRYPOINT ["java", "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", "--add-opens=java.base/java.util.zip=ALL-UNNAMED", "-cp", "/app/test-classes:/app/classes:/app/lib/*"]
CMD ["com.exchange.core.api.trading.infrastructure.aeron.FundingVerificationNodeKt", "--cluster-env"]
