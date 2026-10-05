FROM amazonlinux:2023 AS builder

RUN dnf install -y java-21-amazon-corretto-devel maven git gcc gcc-c++ make rust cargo \
	&& dnf clean all \
	&& rm -rf /var/cache/dnf
ENV JAVA_HOME=/usr/lib/jvm/java-21-amazon-corretto.x86_64
ENV PATH=${JAVA_HOME}/bin:${PATH}
COPY pom.xml /build/pom.xml
COPY src /build/src
WORKDIR /build
RUN mvn -q package -DskipTests
RUN git clone --depth 1 --branch v0.5.2 https://github.com/aws/aws-nitro-enclaves-nsm-api.git /tmp/aws-nitro-enclaves-nsm-api \
	&& cd /tmp/aws-nitro-enclaves-nsm-api \
	&& make nsm-lib \
	&& install -m 0755 target/release/libnsm.so /tmp/libnsm.so \
	&& rm -rf /tmp/aws-nitro-enclaves-nsm-api /root/.cargo /root/.rustup

FROM amazonlinux:2023

RUN dnf install -y java-21-amazon-corretto-headless ca-certificates \
	&& dnf clean all \
	&& rm -rf /var/cache/dnf
COPY --from=builder /tmp/libnsm.so /usr/lib64/libnsm.so
COPY --from=builder /build/target/enclave-service-0.1.0-SNAPSHOT.jar /app/enclave-service.jar

WORKDIR /app
RUN cp /etc/pki/tls/certs/ca-bundle.crt /app/source-ca-bundle.pem
ENV SOURCE_CA_BUNDLE=/app/source-ca-bundle.pem
ENTRYPOINT ["java", "-jar", "/app/enclave-service.jar"]
