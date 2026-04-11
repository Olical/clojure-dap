# Clojure CIDER DAP server [![Clojars Project](https://img.shields.io/clojars/v/uk.me.oli/clojure-dap.svg)](https://clojars.org/uk.me.oli/clojure-dap)

A Debug Adapter Protocol server for Clojure. Enables debugging UIs in Neovim, Helix, VS Code, and any other DAP-capable editor via CIDER's nREPL debug middleware.

> **Beta software.** This works end-to-end but is under active development. [Feedback welcome.](https://github.com/Olical/clojure-dap/issues)

## Prerequisites

- A running nREPL server with [CIDER middleware](https://github.com/clojure-emacs/cider-nrepl)
- Java 21+ and Clojure CLI

## Installation

clojure-dap is published to [Clojars](https://clojars.org/uk.me.oli/clojure-dap). No separate install is needed — the Clojure CLI fetches and caches the dependency on first run. The server is started with:

```bash
clojure -Sdeps '{:deps {uk.me.oli/clojure-dap {:mvn/version "RELEASE"}}}' -X clojure-dap.main/run
```

Pin to a specific version for reproducibility:

```bash
clojure -Sdeps '{:deps {uk.me.oli/clojure-dap {:mvn/version "0.1.311"}}}' -X clojure-dap.main/run
```

Versions follow `0.1.<git-rev-count>` — each release is a higher number. Use `"RELEASE"` to always get the latest, or pin a version in your editor config for stability.

## Editor Setup

### Neovim

Requires [nvim-dap](https://github.com/mfussenegger/nvim-dap). See its docs for keybindings and UI options like [nvim-dap-ui](https://github.com/rcarriga/nvim-dap-ui).

```lua
local dap = require('dap')

dap.adapters.clojure = {
  type = 'executable',
  command = 'clojure',
  args = {
    '-Sdeps', '{:deps {uk.me.oli/clojure-dap {:mvn/version "RELEASE"}}}',
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

### Helix

Helix has built-in (experimental) DAP support. Add to `~/.config/helix/languages.toml`:

```toml
[[language]]
name = "clojure"

[language.debugger]
name = "clojure-dap"
transport = "stdio"
command = "clojure"
args = ["-Sdeps", "{:deps {uk.me.oli/clojure-dap {:mvn/version \"RELEASE\"}}}", "-X", "clojure-dap.main/run"]

[[language.debugger.templates]]
name = "Attach to nREPL"
request = "attach"
args = {}
```

### VS Code

An unpublished extension is included in `vscode-extension/`. Build and install it with `mise run vscode-package` then `code --install-extension vscode-extension/clojure-dap-0.0.1.vsix`.

## Usage

1. Start your nREPL server with CIDER middleware
2. Set breakpoints in your editor
3. Attach the debug adapter
4. Trigger the breakpointed code from a REPL
5. Inspect variables, evaluate expressions, step through code

By default clojure-dap connects to `127.0.0.1` and reads the port from `.nrepl-port`. You can pass explicit connection options in the attach config:

```json
{
  "clojure_dap": {
    "type": "nrepl",
    "nrepl": { "host": "127.0.0.1", "port": 7888 }
  }
}
```

## What's Supported

Set breakpoints, hit them, inspect locals, evaluate expressions with access to local variables, step over/in/out, continue. See the protocol checklist below for details.

## Development

Tools are managed by [mise](https://mise.jdx.dev/). Run `mise install` to set up Java and Clojure CLI.

```bash
mise run test       # Run all tests
mise run format     # Format code
mise run repl       # Start dev REPL
mise run outdated   # Check for outdated deps
```

See [doc/architecture.md](doc/architecture.md) for how DAP maps to CIDER's debug protocol.

## Protocol Support

### Requests

- [x] Attach
- [x] ConfigurationDone
- [x] Continue
- [x] Disconnect
- [x] Evaluate
- [x] Initialize
- [x] Next
- [x] Scopes
- [x] SetBreakpoints
- [x] SetExceptionBreakpoints
- [x] StackTrace
- [x] StepIn
- [x] StepOut
- [x] Threads
- [x] Variables

### Events

- [x] Initialized
- [x] Output
- [x] Stopped
- [x] Terminated

## License

[Unlicense](UNLICENSE)
