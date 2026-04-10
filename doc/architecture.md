# Architecture: DAP to CIDER Debug Protocol Mapping

This document describes how clojure-dap maps the Debug Adapter Protocol (DAP) to CIDER's nREPL debug middleware.

## Overview

DAP clients (Neovim, VS Code) speak the Debug Adapter Protocol over stdin/stdout. clojure-dap translates DAP requests into CIDER nREPL operations and translates CIDER debug events back into DAP events.

```
DAP Client <--stdin/stdout--> clojure-dap <--nREPL TCP--> CIDER middleware
```

## Session Lifecycle

1. **Initialize**: DAP client sends `initialize` request. Server responds with capabilities.
2. **Attach**: Client sends `attach` with nREPL connection info. Server connects to the running nREPL server, creates a session, and calls `init-debugger` to register for debug events.
3. **Set Breakpoints**: Client sends `setBreakpoints` with file path and line numbers. Server reads the source, inserts `#break` reader macros at those lines, and re-evaluates the forms via nREPL `eval`.
4. **Breakpoint Hit**: When instrumented code executes and hits a `#break`, CIDER sends a `need-debug-input` message. Server translates this to a DAP `stopped` event.
5. **Inspect State**: Client requests `stackTrace`, `scopes`, `variables` to inspect the stopped state. Server reads from the stored breakpoint state.
6. **Resume**: Client sends `continue`, `next`, `stepIn`, or `stepOut`. Server sends `debug-input` to CIDER with the appropriate command.
7. **Disconnect**: Client sends `disconnect`. Server cleans up.

## CIDER Debug Protocol

### init-debugger

Sent once during attach. Opens a long-lived channel that receives debug events from CIDER. The response stream delivers `need-debug-input` messages when breakpoints are hit.

### need-debug-input

When a breakpoint is hit, CIDER sends a message with status `need-debug-input`:

```clojure
{:status      ["need-debug-input"]
 :key         "uuid-string"            ; Unique ID for this debug pause
 :debug-value "42"                     ; pr-short of current expression value
 :coor        [3 2 0]                  ; Position within instrumented form (AST path)
 :locals      [["a" "10"] ["b" "20"]]  ; Local variable names and string values
 :file        "/path/to/file.clj"      ; Source file
 :line        13                        ; Line number
 :column      1                         ; Column number
 :code        "(defn foo [a b] ...)"   ; The instrumented source form
 :input-type  ["continue" "next" ...]  ; Available debug commands
 :session     "session-id"             ; nREPL session
 :original-ns "my.namespace"           ; Namespace context
 :original-id "msg-id"                 ; Original eval message ID
}
```

### debug-input

To resume execution, send:

```clojure
{:op    "debug-input"
 :key   "the-uuid-from-need-debug-input"
 :input ":continue"  ; or ":next", ":in", ":out", ":quit"
}
```

Commands:
- `:continue` - Skip this breakpoint, continue running
- `:continue-all` - Skip all remaining breakpoints
- `:next` - Step over (return current value, continue)
- `:in` - Step into function call
- `:out` - Step out of current sexp
- `:quit` - Abort evaluation

## DAP to CIDER Mapping

### Breakpoint State

When `need-debug-input` arrives, clojure-dap stores the full message as the current "breakpoint state" in an atom on the debuggee. This state is read by subsequent DAP requests:

| DAP Request | Data Source |
|-------------|-------------|
| `stackTrace` | `:file`, `:line`, `:column` from breakpoint state |
| `scopes` | One "Locals" scope, referencing the breakpoint's `:locals` |
| `variables` | The `:locals` array `[["name" "value"] ...]` |
| `evaluate` | Sends `eval` to nREPL in the breakpoint's namespace context |

### Variable References

DAP uses integer `variablesReference` IDs to link scopes to their variables. clojure-dap uses a simple scheme:

- Frame ID = `(hash thread-id)` (derived from nREPL session)
- Scope variablesReference = frame ID (one scope per frame)
- Variables with variablesReference = 0 are leaves (no children)

### Thread Mapping

DAP requires thread IDs. CIDER doesn't expose JVM threads directly. clojure-dap maps nREPL sessions to DAP threads:

- Thread ID = `(hash session-id)`
- Thread name = session-id string

### Resume Commands

| DAP Request | CIDER debug-input |
|-------------|-------------------|
| `continue` | `:continue` |
| `next` | `:next` |
| `stepIn` | `:in` |
| `stepOut` | `:out` |

All resume commands send `debug-input` with the `:key` from the stored breakpoint state, then clear the breakpoint state and emit a DAP `continued` event.

## Message Flow Example

```
Client                    clojure-dap                     CIDER nREPL
  |                           |                               |
  |-- setBreakpoints -------->|                               |
  |                           |-- eval (instrumented code) -->|
  |                           |<-- eval response -------------|
  |<-- setBreakpoints resp ---|                               |
  |                           |                               |
  |  (user triggers code)     |                               |
  |                           |<-- need-debug-input ----------|
  |<-- stopped event ---------|                               |
  |                           |                               |
  |-- stackTrace ------------>|                               |
  |<-- frames [{file,line}] --|                               |
  |                           |                               |
  |-- scopes (frameId) ------>|                               |
  |<-- [{name:"Locals"}] -----|                               |
  |                           |                               |
  |-- variables (ref) ------->|                               |
  |<-- [{name,value}...] -----|                               |
  |                           |                               |
  |-- continue --------------->|                               |
  |                           |-- debug-input :continue ----->|
  |<-- continued event -------|                               |
```
