FROM clojure:temurin-17-tools-deps-1.11.1.1386-alpine

WORKDIR /app/clojure-dap

# Fri 18 Aug 14:20:47 BST 2023

RUN apk add --no-cache rlwrap=0.46.1-r0 

COPY src src
COPY test test
COPY script script
COPY deps.edn deps.edn
COPY tests.edn tests.edn

CMD ["script/run"]
