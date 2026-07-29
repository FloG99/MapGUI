@echo off
rem Gradle's rich console pins a progress bar to the bottom line, so a long-running task sits at
rem "93% EXECUTING" forever and covers the server's own output. Plain console has no bar.
call "%~dp0gradlew.bat" previewServe --console=plain %*
