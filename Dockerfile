FROM clojure:temurin-19-tools-deps-1.11.1.1208-alpine

# Sun 22 Jan 11:38:06 GMT 2023

RUN apk update
RUN apk add rlwrap

ADD src src
ADD test test
ADD script script
ADD deps.edn deps.edn
ADD tests.edn tests.edn

CMD script/run
