# Clojure DAP example project

A minimal project for trying the [Clojure DAP](https://marketplace.visualstudio.com/items?itemName=olical.clojure-dap) VS Code extension end-to-end.

## Try it

1. **Start the nREPL** in a terminal from this directory:

   ```bash
   clojure -M:dev
   ```

   It boots an nREPL server with CIDER middleware, writes `.nrepl-port`, and prints the port. Leave it running.

2. **Open this folder in VS Code** (`code .` from this directory, or **File → Open Folder…**). The bundled `.vscode/launch.json` already has an "Attach to nREPL" config.

3. **Set a breakpoint** in `src/example/core.clj` — click the gutter next to a line inside `analyze` (the `let` binding lines are good).

4. **Attach the debugger** — open Run and Debug (`Ctrl+Shift+D`), make sure "Attach to nREPL" is selected at the top, click the green ▶.

5. **Trigger the breakpointed code** in your nREPL terminal:

   ```clojure
   (require 'example.core :reload)
   (example.core/analyze 5)
   ```

   Execution stops at your breakpoint. Step with F10 (over) / F11 (in) / Shift+F11 (out), continue with F5. Locals appear in the **Variables** pane; evaluate expressions in the **Debug Console** (e.g. `(* class 2)` or `fact`).

## What's in here

- `deps.edn` — minimal project with `nrepl` and `cider-nrepl` under the `:dev` alias.
- `src/dev/repl.clj` — boots `nrepl.server` with the cider-nrepl handler so the DAP server has something to attach to.
- `src/example/core.clj` — three tiny functions to set breakpoints in.
- `.vscode/launch.json` — pre-baked attach config so F5 just works.
