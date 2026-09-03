#!/bin/bash
set -e

Xvfb :99 -screen 0 1280x800x24 &
export DISPLAY=:99
sleep 1

x11vnc -display :99 -forever -nopw -shared -bg -quiet -rfbport 5900

exec java -jar /app/nexus-core.jar
