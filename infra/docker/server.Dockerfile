FROM golang:1.26-alpine AS builder
WORKDIR /app
COPY apps/server/go.mod apps/server/go.sum ./
RUN go mod download
COPY apps/server ./
RUN go build -o /out/api-server ./cmd/api-server

FROM alpine:3.22
WORKDIR /app
COPY --from=builder /out/api-server /usr/local/bin/api-server
EXPOSE 18081
CMD ["api-server"]
