FROM golang:1.20-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /clipmind ./cmd/server

FROM alpine:3.20
RUN adduser -D -u 10001 clipmind
USER clipmind
WORKDIR /app
COPY --from=build /clipmind /usr/local/bin/clipmind
EXPOSE 8080
ENTRYPOINT ["clipmind"]
