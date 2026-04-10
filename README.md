# Clojure CIDER DAP server

A Debug Adapter Protocol server for Clojure, enabling rich debugging UIs in Neovim, Helix, VS Code, and any other DAP-capable editor. Uses CIDER's nREPL debug middleware under the hood.

> **Beta software.** This works end-to-end but is under active development. Please try it and [report issues or feedback](https://github.com/Olical/clojure-dap/issues)!

## Features

- Set breakpoints and hit them
- Inspect local variables when stopped
- Evaluate expressions with access to locals at the breakpoint
- Step over, step in, step out, continue
- Works with any DAP client over stdin/stdout

## Prerequisites

- A running Clojure nREPL server with [CIDER middleware](https://github.com/clojure-emacs/cider-nrepl)
- Java 21+ and Clojure CLI

## Editor Setup

### Neovim

Requires [nvim-dap](https://github.com/mfussenegger/nvim-dap). Optionally add [nvim-dap-ui](https://github.com/rcarriga/nvim-dap-ui) for a full debugging UI.

```lua
local dap = require('dap')

dap.adapters.clojure = {
  type = 'executable',
  command = 'clojure',
  args = {
    '-Sdeps', '{:deps {clojure-dap/clojure-dap {:local/root "/path/to/clojure-dap"}}}',
    '-X', 'clojure-dap.main/run',
  },
}

dap.configurations.clojure = {
  {
    name = 'Attach to nREPL',
    type = 'clojure',
    request = 'attach',
  },
}
```

Keybindings (example):

| Key | Action |
|-----|--------|
| `<leader>db` | Toggle breakpoint |
| `<leader>dc` | Continue / start debug session |
| `<leader>dn` | Step over |
| `<leader>di` | Step into |
| `<leader>do` | Step out |
| `<leader>dr` | Open DAP REPL |
| `<leader>dx` | Terminate session |

### Helix

Helix has built-in (experimental) DAP support. Add to `~/.config/helix/languages.toml`:

```toml
[[language]]
name = "clojure"

[language.debugger]
name = "clojure-dap"
transport = "stdio"
command = "clojure"
# Adjust the path to your clojure-dap checkout
args = ["-Sdeps", "{:deps {clojure-dap/clojure-dap {:local/root \"/path/to/clojure-dap\"}}}", "-X", "clojure-dap.main/run"]

[[language.debugger.templates]]
name = "Attach to nREPL"
request = "attach"
args = {}
```

Use `:debug-start` to begin a session.

### VS Code

A VS Code extension is not yet available. You can use a generic DAP extension with:

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Attach to nREPL",
      "type": "clojure-dap",
      "request": "attach"
    }
  ]
}
```

## Usage

1. Start your Clojure project's nREPL server with CIDER middleware
2. Open a Clojure file in your editor and set breakpoints
3. Start the debug adapter (attach to nREPL)
4. Trigger the breakpointed code (e.g. from a REPL)
5. Inspect variables, evaluate expressions, step through code
6. Continue or disconnect

### Attach Arguments

The `attach` request accepts an optional `clojure_dap` argument:

```json
{
  "clojure_dap": {
    "type": "nrepl",
    "nrepl": {
      "host": "127.0.0.1",
      "port": 7888
    }
  }
}
```

If omitted, clojure-dap connects to `127.0.0.1` and reads the port from `.nrepl-port` in the current directory.

## Development

See [CLAUDE.md](CLAUDE.md) for development commands and architecture overview. See [doc/architecture.md](doc/architecture.md) for details on how DAP maps to CIDER's nREPL debug protocol.

## Protocol Support

### Base Protocol

- [ ] Cancel
- [x] ErrorResponse
- [x] Event
- [x] ProtocolMessage
- [x] Request
- [x] Response

### Events

- [ ] Breakpoint
- [ ] Capabilities
- [ ] Continued
- [ ] Exited
- [x] Initialized
- [ ] Invalidated
- [ ] LoadedSource
- [ ] Memory
- [ ] Module
- [x] Output
- [ ] Process
- [ ] ProgressEnd
- [ ] ProgressStart
- [ ] ProgressUpdate
- [x] Stopped
- [x] Terminated
- [ ] Thread

### Requests

- [x] Attach
- [ ] BreakpointLocations
- [ ] Completions
- [x] ConfigurationDone
- [x] Continue
- [ ] DataBreakpointInfo
- [ ] Disassemble
- [x] Disconnect
- [x] Evaluate
- [ ] ExceptionInfo
- [ ] Goto
- [ ] GotoTargets
- [x] Initialize
- [ ] Launch
- [ ] LoadedSources
- [ ] Modules
- [x] Next
- [ ] Pause
- [ ] ReadMemory
- [ ] Restart
- [ ] RestartFrame
- [ ] ReverseContinue
- [x] Scopes
- [x] SetBreakpoints
- [ ] SetDataBreakpoints
- [x] SetExceptionBreakpoints
- [ ] SetExpression
- [ ] SetFunctionBreakpoints
- [ ] SetInstructionBreakpoints
- [ ] SetVariable
- [ ] Source
- [x] StackTrace
- [ ] StepBack
- [x] StepIn
- [ ] StepInTargets
- [x] StepOut
- [ ] Terminate
- [ ] TerminateThreads
- [x] Threads
- [x] Variables
- [ ] WriteMemory

### Reverse Requests

- [ ] RunInTerminal
- [ ] StartDebugging

## License

[Unlicense](UNLICENSE)
