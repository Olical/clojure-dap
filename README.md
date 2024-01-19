# Clojure CIDER DAP server

Will enable rich, interactive, debugging UIs in Neovim, VS Code and any other
editor that supports DAP. See [nvim-dap-ui][nvim-dap-ui] for an example.

Coming "soon"! Until then, you can use the very basic [Conjure][conjure]
debugger support. It's not a great UI, but you can learn about it on
[the Conjure wiki][conjure-wiki].

Useful links for development:

- https://microsoft.github.io/debug-adapter-protocol/overview
- https://microsoft.github.io/debug-adapter-protocol/specification
- https://github.com/cognitect-labs/anomalies
- https://github.com/metosin/malli
- https://cljdoc.org/d/nrepl/nrepl/1.1.0-alpha1/doc/usage/client
- https://cljdoc.org/d/manifold/manifold/0.3.0/api/manifold.deferred

## Protocol support

### Base Protocol

- [ ] Cancel
- [x] ErrorResponse
- [x] Event
- [x] ProtocolMessage
- [x] Request
- [x] Response

### Events

- [ ] Breakpoint
- [ ] Capabilities
- [ ] Continued
- [ ] Exited
- [x] Initialized
- [ ] Invalidated
- [ ] LoadedSource
- [ ] Memory
- [ ] Module
- [x] Output
- [ ] Process
- [ ] ProgressEnd
- [ ] ProgressStart
- [ ] ProgressUpdate
- [ ] Stopped
- [x] Terminated
- [ ] Thread

### Requests

- [x] Attach
- [ ] BreakpointLocations
- [ ] Completions
- [x] ConfigurationDone
- [ ] Continue
- [ ] DataBreakpointInfo
- [ ] Disassemble
- [x] Disconnect
- [x] Evaluate
- [ ] ExceptionInfo
- [ ] Goto
- [ ] GotoTargets
- [x] Initialize
- [ ] Launch
- [ ] LoadedSources
- [ ] Modules
- [ ] Next
- [ ] Pause
- [ ] ReadMemory
- [ ] Restart
- [ ] RestartFrame
- [ ] ReverseContinue
- [ ] Scopes
- [ ] SetBreakpoints
- [ ] SetDataBreakpoints
- [ ] SetExceptionBreakpoints
- [ ] SetExpression
- [ ] SetFunctionBreakpoints
- [ ] SetInstructionBreakpoints
- [ ] SetVariable
- [ ] Source
- [ ] StackTrace
- [ ] StepBack
- [ ] StepIn
- [ ] StepInTargets
- [ ] StepOut
- [ ] Terminate
- [ ] TerminateThreads
- [ ] Threads
- [ ] Variables
- [ ] WriteMemory

### Reverse Requests

- [ ] RunInTerminal
- [ ] StartDebugging

[nvim-dap-ui]: https://github.com/rcarriga/nvim-dap-ui
[conjure]: https://github.com/Olical/conjure
[conjure-wiki]: https://github.com/Olical/conjure/wiki/Clojure-nREPL-CIDER-debugger
