const vscode = require("vscode");

function activate(context) {
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory("clojure-dap", {
      createDebugAdapterDescriptor(_session) {
        const config = vscode.workspace.getConfiguration("clojure-dap");
        const dapPath = config.get("path");

        if (!dapPath) {
          vscode.window.showErrorMessage(
            'Set "clojure-dap.path" in settings to the path of your clojure-dap checkout.'
          );
          return undefined;
        }

        const deps = `{:deps {clojure-dap/clojure-dap {:local/root "${dapPath}"}}}`;

        return new vscode.DebugAdapterExecutable("clojure", [
          "-Sdeps",
          deps,
          "-X",
          "clojure-dap.main/run",
        ]);
      },
    })
  );
}

function deactivate() {}

module.exports = { activate, deactivate };
