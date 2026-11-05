#!/bin/zsh
set -e
cd "${0:A:h}"
if command -v node >/dev/null 2>&1; then
  node launch.mjs "$@"
else
  print 'Node.js 22 or newer is required. Install Node, then reopen this launcher.'
  read '?Press Enter to close.'
fi
