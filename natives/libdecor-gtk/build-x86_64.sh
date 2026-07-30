#!/bin/bash
# Build patched libdecor-gtk.so for x86_64 (native)
#
# Prerequisites (Arch Linux):
#   sudo pacman -S --needed meson ninja gcc pkg-config wayland-protocols gtk3 cairo pango dbus
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

if [ ! -d "libdecor-src" ]; then
    echo "Cloning libdecor v0.2.5..."
    git clone --depth 1 --branch 0.2.5 https://gitlab.freedesktop.org/libdecor/libdecor.git libdecor-src
    # Patch out the thread check
    sed -i 's/if (getpid () != gettid ())/if (0)/' libdecor-src/src/plugins/gtk/libdecor-gtk.c
    echo "Patched libdecor-gtk.c"
fi

cd libdecor-src
rm -rf build-x86_64
meson setup build-x86_64 -Ddemo=false -Ddbus=enabled
ninja -C build-x86_64 src/plugins/gtk/libdecor-gtk.so

echo ""
echo "=== BUILD SUCCESS ==="
file build-x86_64/src/plugins/gtk/libdecor-gtk.so
