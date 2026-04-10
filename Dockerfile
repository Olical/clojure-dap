FROM clojure:temurin-25-tools-deps-alpine

WORKDIR /app/clojure-dap

RUN apk add --no-cache rlwrap

COPY src src
COPY test test
COPY deps.edn deps.edn
COPY tests.edn tests.edn

CMD ["clojure", "-X", "clojure-dap.main/run"]
