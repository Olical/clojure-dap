(ns clojure-dap.server
  "Core of the system, give it some IO to communicate with the client through and an nREPL server to drive from user inputs and it'll handle the rest. Understands both the DAP and nREPL messages, relaying ideas between the two.")
