const vscode = require("vscode");

function activate(context) {
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory("clojure-dap", {
      createDebugAdapterDescriptor(_session) {
        const config = vscode.workspace.getConfiguration("clojure-dap");
        const localPath = config.get("path");
        const version = config.get("version");

        const dep = localPath
          ? `clojure-dap/clojure-dap {:local/root "${localPath}"}`
          : `uk.me.oli/clojure-dap {:mvn/version "${version}"}`;

        const deps = `{:deps {${dep}}}`;

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
