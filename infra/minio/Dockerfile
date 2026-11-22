# Official source release; upstream public binary images are no longer available.
# Source and AGPL-3.0 license: https://github.com/minio/minio/tree/RELEASE.2025-10-15T17-29-55Z
FROM golang:1.24.9-alpine3.22 AS build
RUN apk add --no-cache git ca-certificates
ENV CGO_ENABLED=0 GOMAXPROCS=2
RUN go install -p 2 github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z

FROM alpine:3.22.2
RUN apk add --no-cache ca-certificates curl
COPY --from=build /go/bin/minio /usr/local/bin/minio
EXPOSE 9000
ENTRYPOINT []
CMD ["minio", "server", "/minio_data"]
