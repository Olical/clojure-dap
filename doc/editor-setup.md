# Editor Setup

clojure-dap communicates over stdin/stdout using the Debug Adapter Protocol. Any DAP-capable editor can use it.

## Prerequisites

1. A running Clojure nREPL server with CIDER middleware
2. clojure-dap available on your PATH (or specify the full path)

To run clojure-dap: `mise run dap` (or `clojure -X clojure-dap.main/run`)

## Neovim (nvim-dap)

Requires [nvim-dap](https://github.com/mfussenegger/nvim-dap).

```lua
local dap = require('dap')

dap.adapters.clojure = {
  type = 'executable',
  command = 'clojure',
  args = { '-X', 'clojure-dap.main/run' },
}

dap.configurations.clojure = {
  {
    name = 'Attach to nREPL',
    type = 'clojure',
    request = 'attach',
    -- clojure-dap reads .nrepl-port by default
  },
  {
    name = 'Attach to nREPL (custom port)',
    type = 'clojure',
    request = 'attach',
    clojure_dap = {
      type = 'nrepl',
      nrepl = {
        host = '127.0.0.1',
        port = 7888,
      },
    },
  },
}
```

## Helix

Helix has built-in (experimental) DAP support. Configure in `~/.config/helix/languages.toml`:

```toml
[[language]]
name = "clojure"

[language.debugger]
name = "clojure-dap"
transport = "stdio"
command = "clojure"
args = ["-X", "clojure-dap.main/run"]

[[language.debugger.templates]]
name = "Attach to nREPL"
request = "attach"
args = {}

[[language.debugger.templates]]
name = "Attach to nREPL (custom port)"
request = "attach"
completion = [
  { name = "port", default = "7888" }
]
[language.debugger.templates.args.clojure_dap]
type = "nrepl"
[language.debugger.templates.args.clojure_dap.nrepl]
host = "127.0.0.1"
port = "{0}"
```

## VS Code

Use a generic DAP extension or create a `launch.json`:

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

Note: A VS Code extension would be needed to register the `clojure-dap` adapter type. This is not yet available.

## Workflow

1. Start your Clojure project's nREPL server (with CIDER middleware)
2. Set breakpoints in your editor
3. Start the debug adapter (attach configuration)
4. Trigger the code that hits a breakpoint
5. Inspect variables, evaluate expressions, step through code
6. Continue or disconnect

## Attach Arguments

The `attach` request accepts an optional `clojure_dap` argument:

```json
{
  "clojure_dap": {
    "type": "nrepl",
    "nrepl": {
      "host": "127.0.0.1",
      "port": 7888,
      "port-file-name": ".nrepl-port",
      "root-dir": "."
    }
  }
}
```

If omitted, clojure-dap defaults to connecting to `127.0.0.1` and reading the port from `.nrepl-port` in the current directory.
