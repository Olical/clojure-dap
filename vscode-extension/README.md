# Clojure DAP

[Debug Adapter Protocol](https://microsoft.github.io/debug-adapter-protocol/) support for Clojure inside VS Code, backed by [clojure-dap](https://github.com/Olical/clojure-dap) and CIDER's nREPL debug middleware. Set breakpoints, step through code, evaluate expressions, inspect locals and stacks — driven from your existing nREPL session.

The extension is a thin client. clojure-dap is a standalone JVM that connects to your existing nREPL — start that however you like (Conjure, Calva, `lein repl`, `clj` with CIDER middleware, mise dev, etc.) so it writes `.nrepl-port` to your project root, and the DAP server picks the port up automatically. Or pass `host`/`port` explicitly in `launch.json`.

## Prerequisites

- A running nREPL server with [CIDER middleware](https://github.com/clojure-emacs/cider-nrepl)
- Java 21+ and Clojure CLI on the launched command's path

## Settings

| Setting | Default | Purpose |
| --- | --- | --- |
| `clojure-dap.command` | `clojure` | Executable launched as the DAP server. Set to an absolute path if your `clojure` binary isn't on the GUI PATH (mise/asdf users) or to a wrapper that activates project tooling. |
| `clojure-dap.args` | `["-Sdeps", "{:deps {uk.me.oli/clojure-dap {:mvn/version \"RELEASE\"}}}", "-X", "clojure-dap.main/run"]` | Arguments passed to the launch command. Override the `:mvn/version` to pin a specific clojure-dap release. |

Edit them from **Settings → Extensions → Clojure DAP** and scope to the project via the **Workspace** tab — workspace settings land in `.vscode/settings.json` so a team can share them.

## Launch configuration

Use the bundled "Clojure DAP: Attach to nREPL" snippet, or add to `launch.json`:

```json
{
  "type": "clojure-dap",
  "request": "attach",
  "name": "Attach to nREPL"
}
```

By default the DAP server connects to `127.0.0.1` and reads the port from `.nrepl-port`. You can pass explicit connection options:

```json
{
  "type": "clojure-dap",
  "request": "attach",
  "name": "Attach to nREPL",
  "clojure_dap": {
    "type": "nrepl",
    "nrepl": { "host": "127.0.0.1", "port": 7888 }
  }
}
```

## Keep stdout clean

The launched process talks to VS Code over stdio using DAP's JSON-RPC framing protocol. The default invocation is safe; if you wrap `clojure` in your own `clojure-dap.command` script, make sure the wrapper itself doesn't print banners, status lines, or its own logs to stdout — anything not in the DAP frame format will corrupt the transport.

## Usage

1. Start your nREPL server with CIDER middleware
2. Set breakpoints in your editor
3. Run "Attach to nREPL" from the debug panel
4. Trigger the breakpointed code from a REPL
5. Inspect variables, evaluate expressions, step through code

## See also

- [Typed Clojure LSP](https://marketplace.visualstudio.com/items?itemName=olical.typedclojure-lsp) — companion extension that brings [Typed Clojure](https://github.com/typedclojure/typedclojure) type checking into VS Code over LSP.

## License

Released into the public domain via the [Unlicense](LICENSE).
