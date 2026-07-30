#!/bin/bash
# Build patched libdecor-gtk.so for aarch64 via Docker
#
# Prerequisites (Arch Linux):
#   sudo pacman -S --needed docker qemu-user-static qemu-user-static-binfmt
#   sudo systemctl restart systemd-binfmt
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
rm -rf build-aarch64

sudo docker run --rm --platform linux/arm64 \
    -v "$(pwd)":/build \
    debian:bookworm bash -c "
apt-get update &&
apt-get install -y meson ninja-build gcc pkg-config libwayland-dev libgtk-3-dev libcairo2-dev libpango1.0-dev libdbus-1-dev wayland-protocols &&
cd /build &&
meson setup build-aarch64 -Ddemo=false -Ddbus=enabled &&
ninja -C build-aarch64 src/plugins/gtk/libdecor-gtk.so
"

echo ""
echo "=== BUILD SUCCESS ==="
file build-aarch64/src/plugins/gtk/libdecor-gtk.so
