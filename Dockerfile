FROM clojure:temurin-18-tools-deps-1.11.1.1155-alpine

RUN apk update
RUN apk add rlwrap

ADD src src
ADD test test
ADD script script
ADD deps.edn deps.edn
ADD tests.edn tests.edn

CMD script/run
