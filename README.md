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

clojure-dap is a standalone JVM the editor spawns when you start a debug session. It connects to your existing nREPL — start that however you like (Conjure, Calva, `lein repl`, `clj` with CIDER middleware, mise dev, etc.) so it writes `.nrepl-port` to your project root, and clojure-dap will pick the port up automatically. You can also pass `host`/`port` explicitly in the editor's attach config.

> **Keep stdout clean.** The DAP server talks to the editor over stdio using JSON-RPC framing. The launch command itself must not print anything to stdout. The default `clojure -Sdeps … -X clojure-dap.main/run` invocation is safe; if you wrap it in your own script (e.g. for `mise`-managed Clojure) make sure that wrapper doesn't print banners, status, or its own logs to stdout.

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

Install [Clojure DAP from the Visual Studio Marketplace](https://marketplace.visualstudio.com/items?itemName=olical.clojure-dap), or from the command line:

```bash
code --install-extension olical.clojure-dap
```

For development, you can build and install a local `.vsix` from the `vscode-extension/` directory with `mise run vscode-package`, then `code --install-extension vscode-extension/clojure-dap-<version>.vsix`.

The extension spawns the DAP server on demand when you start a debug session. Two settings tune the launch:

| Setting | Default | Purpose |
| --- | --- | --- |
| `clojure-dap.command` | `clojure` | Executable launched as the DAP server. Set to an absolute path if your `clojure` binary isn't on the GUI PATH (mise/asdf users) or to a wrapper that activates project tooling. |
| `clojure-dap.args` | `["-Sdeps", "{:deps {uk.me.oli/clojure-dap {:mvn/version \"RELEASE\"}}}", "-X", "clojure-dap.main/run"]` | Arguments passed to the launch command. Override the `:mvn/version` to pin a specific clojure-dap release. |

Edit them from **Settings → Extensions → Clojure DAP**, scoped per workspace via the **Workspace** tab. Workspace settings land in `.vscode/settings.json`, so you can commit them with the project.

## Logs

The DAP server logs at `:trace` level to a per-OS file path, plus stderr (which the editor captures). Defaults:

- Linux: `${XDG_STATE_HOME:-~/.local/state}/clojure-dap/clojure-dap.log`
- macOS: `~/Library/Logs/clojure-dap/clojure-dap.log`
- Windows: `%LOCALAPPDATA%\clojure-dap\clojure-dap.log`

Set `CLOJURE_DAP_LOG=/some/path/clojure-dap.log` to override. The resolved path is logged on startup so the editor's debug console shows it too.

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
