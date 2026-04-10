# Editor Setup

clojure-dap communicates over stdin/stdout using the Debug Adapter Protocol. Any DAP-capable editor can use it.

## Prerequisites

1. A running Clojure nREPL server with CIDER middleware
2. clojure-dap on the classpath (see Running below)

## Running clojure-dap

For local development, use `-Sdeps` with a `:local/root` path:

```bash
clojure -Sdeps '{:deps {clojure-dap/clojure-dap {:local/root "/path/to/clojure-dap"}}}' -X clojure-dap.main/run
```

Replace `/path/to/clojure-dap` with the actual path to your checkout.

## Neovim (nvim-dap)

Requires [nvim-dap](https://github.com/mfussenegger/nvim-dap).

```lua
local dap = require('dap')

-- Adjust the path to your clojure-dap checkout
local clojure_dap_path = '/path/to/clojure-dap'

dap.adapters.clojure = {
  type = 'executable',
  command = 'clojure',
  args = {
    '-Sdeps', '{:deps {clojure-dap/clojure-dap {:local/root "' .. clojure_dap_path .. '"}}}',
    '-X', 'clojure-dap.main/run',
  },
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
# Adjust the path to your clojure-dap checkout
args = ["-Sdeps", "{:deps {clojure-dap/clojure-dap {:local/root \"/path/to/clojure-dap\"}}}", "-X", "clojure-dap.main/run"]

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
