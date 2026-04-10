# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

clojure-dap is a Debug Adapter Protocol (DAP) server for Clojure, bridging DAP-compatible editors (Neovim, VS Code) to Clojure's CIDER debugger via nREPL. It communicates with clients over stdin/stdout using the DAP wire format (JSON with Content-Length headers).

## Commands

```bash
./script/kaocha           # Run all tests (Kaocha runner)
./script/kaocha --focus clojure-dap.protocol-test  # Run a single test namespace
./script/format           # Format code (cljfmt)
./script/repl             # Start dev REPL (nREPL + CIDER + Rebel Readline)
./script/run              # Run the DAP server (reads stdin, writes stdout)
./script/outdated         # Check for outdated dependencies
```

## Architecture

**Data flow:** Reader (stdin) -> byte stream -> char stream -> message-reader (parses DAP frames) -> handler dispatch -> response messages -> render (JSON + headers) -> Writer (stdout)

### Key modules

- **`main.clj`** - CLI entrypoint. Configures Timbre logging (stderr + `~/.cache/nvim/clojure-dap.log`), enables Malli instrumentation, wires stdin/stdout to the server.
- **`server.clj`** - Orchestrates three manifold threads (reader, writer, message-reader) connected by streams. `run-io-wrapped` is the main entry; `run` handles message routing.
- **`protocol.clj`** - Parses/renders DAP wire format. Validates all messages against the DAP JSON schema (converted to Malli). `supported-messages` lists all known message types.
- **`schema.clj`** - Global Malli registry. Converts DAP JSON schema (`resources/clojure-dap/dap-json-schema.json`) to Malli schemas. Provides `result` wrapper type (value-or-anomaly) and `validate` function.
- **`server/handler.clj`** - Multimethod `handle-client-input*` dispatches on `:command`. Each handler receives `{:input, :debuggee!, :output-stream, :resp}` and returns a seq of response messages.
- **`debuggee.clj`** - Protocol interface (as a map of functions): `set-breakpoints`, `evaluate`, `threads`, `stack-trace`, `scopes`, `variables`.
- **`debuggee/nrepl.clj`** - Real implementation connecting to a running nREPL server with CIDER middleware.
- **`debuggee/fake.clj`** - Test double with configurable responses/failures.
- **`source.clj`** - Clojure source parsing; inserts `#break` markers at breakpoint lines.
- **`stream.clj`** - Wraps Java IO in manifold streams; `read-message` parses DAP frames (header + body).

### Patterns

- **Error handling**: Uses `nom` (monadic anomalies) throughout. Functions return either a value or a `nom/fail` anomaly. `nom/with-nom` short-circuits on anomaly. Anomaly kinds come from `cognitect/anomalies`.
- **Schema validation**: `schema/validate` returns nil on success or an anomaly. `schema/result` wraps any schema as `[:or schema ::anomaly]`. `mx/defn` (Malli experimental) adds runtime type checking to function signatures.
- **Case conversion**: Wire format uses PascalCase/camelCase; Clojure code uses kebab-case. `camel-snake-kebab` handles conversion. DAP JSON schema keys map to PascalCase Malli schema names.
- **Async**: Manifold streams and deferreds. `util/with-thread` names threads for debugging. Server supports sync and async modes via `:async?` flag.
- **Logging**: All logging goes to stderr (stdout reserved for DAP protocol). Timbre with SLF4J bridges captures all Java logging.

## Testing

Tests use `clojure.test` with `matcher-combinators` for assertions and `spy/core` for function call tracking. Kaocha runs with randomized order. The `kaocha_hooks.clj` pre-run hook enables Malli instrumentation with pretty error reporting during tests.
