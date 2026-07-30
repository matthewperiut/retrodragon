#!/bin/bash
# Build libcenter.so for x86_64 natively on Arch Linux
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Ensure build prerequisites
if ! command -v cc &>/dev/null || ! command -v pkg-config &>/dev/null; then
    echo "Installing base-devel..."
    sudo pacman -S --needed --noconfirm base-devel
fi
if ! pacman -Qi wayland &>/dev/null; then
    echo "Installing wayland..."
    sudo pacman -S --needed --noconfirm wayland
fi
if ! pacman -Qi wayland-protocols &>/dev/null; then
    echo "Installing wayland-protocols..."
    sudo pacman -S --needed --noconfirm wayland-protocols
fi

make clean
make

echo ""
echo "=== BUILD SUCCESS ==="
file ../../src/main/resources/natives/linux-x86_64/libcenter.so
