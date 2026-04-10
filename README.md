# Clojure CIDER DAP server

A Debug Adapter Protocol server for Clojure. Enables debugging UIs in Neovim, Helix, VS Code, and any other DAP-capable editor via CIDER's nREPL debug middleware.

> **Beta software.** This works end-to-end but is under active development. [Feedback welcome.](https://github.com/Olical/clojure-dap/issues)

## Prerequisites

- A running nREPL server with [CIDER middleware](https://github.com/clojure-emacs/cider-nrepl)
- Java 21+ and Clojure CLI

## Installation

For now, clone the repo and reference it locally. Future plans include Clojars publishing and GraalVM native image distribution once things stabilise.

```bash
git clone https://github.com/Olical/clojure-dap.git
```

The server is started via the Clojure CLI with a local dep:

```bash
clojure -Sdeps '{:deps {clojure-dap/clojure-dap {:local/root "/path/to/clojure-dap"}}}' -X clojure-dap.main/run
```

## Editor Setup

### Neovim

Requires [nvim-dap](https://github.com/mfussenegger/nvim-dap). See its docs for keybindings and UI options like [nvim-dap-ui](https://github.com/rcarriga/nvim-dap-ui).

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

### Helix

Helix has built-in (experimental) DAP support. Add to `~/.config/helix/languages.toml`:

```toml
[[language]]
name = "clojure"

[language.debugger]
name = "clojure-dap"
transport = "stdio"
command = "clojure"
args = ["-Sdeps", "{:deps {clojure-dap/clojure-dap {:local/root \"/path/to/clojure-dap\"}}}", "-X", "clojure-dap.main/run"]

[[language.debugger.templates]]
name = "Attach to nREPL"
request = "attach"
args = {}
```

### VS Code

An unpublished extension is included in `vscode-extension/`. Build and install it with `mise run vscode-package` then `code --install-extension vscode-extension/clojure-dap-0.0.1.vsix`. Set `clojure-dap.path` in VS Code settings to your checkout path.

## Usage

1. Start your nREPL server with CIDER middleware
2. Set breakpoints in your editor
3. Attach the debug adapter
4. Trigger the breakpointed code from a REPL
5. Inspect variables, evaluate expressions, step through code

By default clojure-dap connects to `127.0.0.1` and reads the port from `.nrepl-port`. You can pass explicit connection options via the attach arguments (see [doc/editor-setup.md](doc/editor-setup.md)).

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
