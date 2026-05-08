const vscode = require("vscode");

function activate(context) {
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory("clojure-dap", {
      createDebugAdapterDescriptor(_session) {
        const folders = vscode.workspace.workspaceFolders;
        const cwd = folders && folders.length > 0 ? folders[0].uri.fsPath : undefined;

        const config = vscode.workspace.getConfiguration("clojure-dap");
        const command = config.get("command");
        const args = config.get("args") ?? [];

        return new vscode.DebugAdapterExecutable(command, args, cwd ? { cwd } : undefined);
      },
    }),
  );
}

function deactivate() {}

module.exports = { activate, deactivate };
