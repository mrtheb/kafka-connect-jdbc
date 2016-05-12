FROM anapsix/alpine-java:jre8

RUN apk update && apk upgrade && \
    apk add --no-cache curl

ENV CONFLUENT_VERSION 2.1
ENV CONFLUENT_FULL_VERSION 2.1.0-alpha1
ENV SCALA_VERSION 2.11.7
ENV CONFLUENT_HOME /opt/confluent-$CONFLUENT_FULL_VERSION

RUN cd /opt && \
    curl -SL http://packages.confluent.io/archive/$CONFLUENT_VERSION/confluent-$CONFLUENT_FULL_VERSION-$SCALA_VERSION.tar.gz | \
    tar -xz

WORKDIR $CONFLUENT_HOME

# save some space
RUN rm -rf \
    share/java/camus \
    share/java/kafka-connect-hdfs \
    share/java/schema-registry

COPY target/kafka-connect-jdbc-$CONFLUENT_FULL_VERSION-package/share/java/kafka-connect-jdbc/*.jar \
    share/java/kafka-connect-jdbc/

COPY config/connect-*.properties /etc/kafka-connect/
