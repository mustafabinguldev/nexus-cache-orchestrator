#!/bin/bash
set -e

rm -f /tmp/.X99-lock /tmp/.X11-unix/X99
mkdir -p ~/.fluxbox ~/.vnc
touch ~/.fluxbox/init ~/.fluxbox/keys

Xvfb :99 -screen 0 1280x800x24 &
export DISPLAY=:99
sleep 2

fluxbox > /dev/null 2>&1 &
sleep 1

VNC_PASS="${VNC_PASSWORD:-nexus123}"
x11vnc -storepasswd "$VNC_PASS" ~/.vnc/passwd

x11vnc -display :99 -forever -rfbauth ~/.vnc/passwd -shared -bg -quiet -rfbport 5900

export _JAVA_OPTIONS="-Dsun.java2d.opengl=true -Dawt.useSystemAAFontSettings=on -Dswing.aatext=true"

exec xterm -geometry 130x40+20+20 \
      -bg "#1e1e2e" \
      -fg "#cdd6f4" \
      -fa "Cascadia Code, DejaVu Sans Mono" \
      -fs 11 \
      -title "Nexus Core Orchestrator Console" \
      -e "java -jar /app/nexus-core.jar"