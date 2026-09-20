FROM busybox:1.37.0-musl AS probe
FROM otel/opentelemetry-collector-contrib:0.164.0
COPY --from=probe /bin/busybox /bin/busybox
