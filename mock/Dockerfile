ARG GO_VERSION=1.27
FROM golang:${GO_VERSION} AS build
WORKDIR /src
COPY . .
RUN go vet ./... && go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/mock .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/mock /mock
EXPOSE 8080
ENTRYPOINT ["/mock"]
