# Architecture: DAP to CIDER Debug Protocol Mapping

This document describes how clojure-dap maps the Debug Adapter Protocol (DAP) to CIDER's nREPL debug middleware.

## Overview

DAP clients (Neovim, VS Code) speak the Debug Adapter Protocol over stdin/stdout. clojure-dap translates DAP requests into CIDER nREPL operations and translates CIDER debug events back into DAP events.

```
DAP Client <--stdin/stdout--> clojure-dap <--nREPL TCP--> CIDER middleware
```

## Dual Transport Architecture

clojure-dap opens **two** TCP connections to the nREPL server:

- **Debug transport**: Dedicated to the `init-debugger` channel and `debug-input` exchanges. Messages are read in a persistent loop that never terminates on "done" status, since the debug channel needs to stay open across multiple breakpoint hits and eval exchanges.
- **Ops transport**: Used for regular operations (`eval`, `ls-sessions`, `setBreakpoints`). Uses standard `nrepl/message` with session filtering.

This separation prevents message interleaving between the long-lived debug stream and regular request/response operations. Each transport has its own nREPL session.

## Session Lifecycle

1. **Initialize**: DAP client sends `initialize` request. Server responds with capabilities.
2. **Attach**: Client sends `attach` with nREPL connection info. Server opens both transports, creates sessions, and sends `init-debugger` on the debug transport.
3. **Set Breakpoints**: Client sends `setBreakpoints` with file path and line numbers. Server reads the source, inserts `#break` reader macros at those lines, and re-evaluates the forms via nREPL `eval` on the ops transport.
4. **Breakpoint Hit**: When instrumented code executes and hits a `#break`, CIDER sends a `need-debug-input` message on the debug transport. Server translates this to a DAP `stopped` event.
5. **Inspect State**: Client requests `stackTrace`, `scopes`, `variables` to inspect the stopped state. Server reads from the stored breakpoint state.
6. **Evaluate**: If stopped at a breakpoint, uses CIDER's `:eval` debug-input to evaluate with access to local variables (see Debug State Machine below). If not at a breakpoint, uses regular nREPL `eval` on the ops transport.
7. **Resume**: Client sends `continue`, `next`, `stepIn`, or `stepOut`. Server sends `debug-input` via the debug transport.
8. **Disconnect**: Client sends `disconnect`. Server emits `terminated` event.

## CIDER Debug Protocol

### init-debugger

Sent once during attach on the debug transport. Opens a long-lived channel that receives debug events from CIDER. Unlike `nrepl/message` which terminates on "done" status, we read directly from the transport in a loop to keep the channel open.

### need-debug-input

When a breakpoint is hit, CIDER sends a message with status `need-debug-input`:

```clojure
{:status      ["need-debug-input"]
 :key         "uuid-string"            ; Unique ID for this debug pause
 :debug-value "42"                     ; pr-short of current expression value
 :coor        [3 2 0]                  ; Position within instrumented form (AST path)
 :locals      [["a" "10"] ["b" "20"]]  ; Local variable names and string values
 :file        "/path/to/file.clj"      ; Source file
 :line        13                        ; Line number (of the FORM, not the #break)
 :column      1                         ; Column number
 :code        "(defn foo [a b] ...)"   ; The instrumented source form
 :input-type  ["continue" "next" ...]  ; Available debug commands (vector)
 :session     "session-id"             ; nREPL session
 :original-ns "my.namespace"           ; Namespace context
 :original-id "msg-id"                 ; Original eval message ID
}
```

When CIDER is asking for an expression to evaluate (during the eval debug-input flow), `input-type` is the string `"expression"` instead of a vector.

### debug-input

To resume execution or interact with the debugger, send via the debug transport:

```clojure
{:op      "debug-input"
 :session "debug-session-id"
 :key     "the-uuid-from-need-debug-input"
 :input   ":continue"  ; or ":next", ":in", ":out", ":eval", ":quit"
}
```

Messages are sent via `nrepl.transport/send` directly (not `nrepl/message`) to avoid consuming responses that belong to the init-debugger reader loop.

Commands:
- `:continue` - Skip this breakpoint, continue running
- `:continue-all` - Skip all remaining breakpoints
- `:next` - Step over (return current value, continue)
- `:in` - Step into function call
- `:out` - Step out of current sexp
- `:eval` - Evaluate an expression with access to locals (triggers expression prompt)
- `:quit` - Abort evaluation

## Debug State Machine

The debug state machine manages the init-debugger message handler. It lives in a `debug-state!` atom and transitions are driven by incoming `need-debug-input` messages.

```
                  need-debug-input (vector input-type)
    :idle ──────────────────────────────────────────────> :stopped
                                                            │
              ┌─────── continue/next/step ──────────────────┤
              │                                             │
              v          evaluate request                   │
           :idle <──── (sets up promise, sends              │
                        :eval debug-input)                  │
                                                            v
                                              :awaiting-expression-prompt
                                                            │
                      need-debug-input (input-type "expression")
                      (sends expression via transport/send) │
                                                            v
                                              :awaiting-eval-result
                                                            │
                      need-debug-input (vector input-type)  │
                      (delivers debug-value to promise)     │
                                                            v
                                                        :stopped
                                              (back at breakpoint, no event emitted)
```

### States

| State | Description |
|-------|-------------|
| `:idle` | No breakpoint active |
| `:stopped` | Paused at a breakpoint. Stack trace, scopes, variables, and evaluate are available. |
| `:awaiting-expression-prompt` | Sent `:eval` debug-input, waiting for CIDER to ask for the expression |
| `:awaiting-eval-result` | Sent the expression, waiting for CIDER to return to the breakpoint with the result |

### Evaluate with Locals

When the DAP client sends an `evaluate` request while stopped at a breakpoint:

1. `evaluate` sets `pending-eval` fields on the state and a result promise
2. `evaluate` sends `:eval` debug-input via `transport/send`
3. CIDER sends `need-debug-input` with `input-type "expression"` (the prompt)
4. State machine sends the expression via `transport/send`
5. CIDER evaluates the expression in the breakpoint's local scope
6. CIDER sends `need-debug-input` back at the breakpoint with the result as `debug-value`
7. State machine delivers `debug-value` to the promise
8. `evaluate` returns the result

If not at a breakpoint, `evaluate` falls back to regular nREPL `eval` on the ops transport.

## DAP to CIDER Mapping

### Breakpoint State

When `need-debug-input` arrives, clojure-dap stores the message fields in the debug state's `:breakpoint` map. This state is read by subsequent DAP requests:

| DAP Request | Data Source |
|-------------|-------------|
| `stackTrace` | `:file`, corrected `:line` (see below), `:column`, and frame name from `:code`/`:original-ns` |
| `scopes` | One "Locals" scope, referencing the breakpoint's `:locals` |
| `variables` | The `:locals` array `[["name" "value"] ...]` |
| `evaluate` | Via debug-input `:eval` (with locals) or regular nREPL eval (without) |

### Line Number Correction

CIDER reports `:line` as the start line of the instrumented form (e.g. the `defn`), not the actual `#break` position. clojure-dap corrects this by finding the `#break` marker positions in `:code` and using the `:coor` depth to select the right one. With multiple `#break` markers, deeper `coor` paths select later breaks.

### Frame Names

Stack frame names are derived from the code: for `defn` forms, the frame shows `"namespace/function-name"`. For other forms, it falls back to the `debug-value`.

### Variable References

DAP uses integer `variablesReference` IDs to link scopes to their variables. clojure-dap uses a simple scheme:

- Frame ID = 1 (single frame)
- Scope variablesReference = 1 (one scope per frame)
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

All resume commands send `debug-input` via the debug transport and reset the debug state to `:idle`.

## Message Flow Examples

### Basic Breakpoint Flow

```
Client                    clojure-dap                     CIDER nREPL
  |                           |                               |
  |-- setBreakpoints -------->|                               |
  |                           |-- eval (instrumented) ------->| (ops transport)
  |                           |<-- eval response -------------|
  |<-- setBreakpoints resp ---|                               |
  |                           |                               |
  |  (user triggers code)     |                               |
  |                           |<-- need-debug-input ----------| (debug transport)
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
  |                           |-- debug-input :continue ----->| (debug transport)
```

### Evaluate with Locals Flow

```
Client                    clojure-dap                     CIDER nREPL
  |                           |                               |
  |  (stopped at breakpoint)  |                               |
  |                           |                               |
  |-- evaluate "(inc a)" ---->|                               |
  |                           |-- debug-input :eval --------->| (debug transport)
  |                           |<-- need-debug-input ----------| (input-type "expression")
  |                           |-- debug-input "(inc a)" ----->| (debug transport)
  |                           |<-- need-debug-input ----------| (back at breakpoint,
  |                           |   (debug-value = "11")        |  with eval result)
  |<-- evaluate resp "11" ----|                               |
```
