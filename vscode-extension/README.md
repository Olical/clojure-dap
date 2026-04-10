# Clojure DAP - VS Code Extension

Debug Adapter Protocol support for Clojure via CIDER nREPL.

## Setup

1. Clone [clojure-dap](https://github.com/Olical/clojure-dap)
2. Install this extension (see below)
3. Set `clojure-dap.path` in VS Code settings to your clojure-dap checkout path
4. Start an nREPL server with CIDER middleware in your Clojure project
5. Open a Clojure file, set breakpoints, and run "Attach to nREPL" from the debug panel

## Installing from source

```bash
cd vscode-extension
npm install -g @vscode/vsce
vsce package
code --install-extension clojure-dap-0.0.1.vsix
```

## Configuration

In VS Code settings:

- `clojure-dap.path`: Path to your clojure-dap checkout (required)

In `launch.json`:

```json
{
  "type": "clojure-dap",
  "request": "attach",
  "name": "Attach to nREPL"
}
```

To connect to a specific host/port:

```json
{
  "type": "clojure-dap",
  "request": "attach",
  "name": "Attach to nREPL",
  "clojure_dap": {
    "type": "nrepl",
    "nrepl": {
      "host": "127.0.0.1",
      "port": 7888
    }
  }
}
```
