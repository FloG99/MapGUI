#!/bin/sh
# Gradle's rich console pins a progress bar to the bottom line, so a long-running task sits at
# "93% EXECUTING" forever and covers the server's own output. Plain console has no bar.
exec "$(dirname "$0")/gradlew" previewServe --console=plain "$@"
