ARG GO_VERSION=1.27
FROM golang:${GO_VERSION} AS build
WORKDIR /src/loadgen
COPY loadgen .
RUN go vet ./... && go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/loadgen .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/loadgen /loadgen
ENV RESULTS_DIR=/results
ENTRYPOINT ["/loadgen"]
