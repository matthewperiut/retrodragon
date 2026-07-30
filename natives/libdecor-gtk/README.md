# Patched libdecor-gtk plugin

This is a patched build of libdecor's GTK plugin (`libdecor-gtk.so`) from
[libdecor](https://gitlab.freedesktop.org/libdecor/libdecor) v0.2.5.

## Why?

The upstream `libdecor-gtk.so` plugin refuses to initialize on non-main threads
(`getpid() != gettid()` check in `libdecor-gtk.c`). The JVM runs Java code on a
thread that is not the OS main thread, so the plugin always fails with
"failed to init" when loaded from Java/GLFW.

This patched version removes that thread check so the plugin works inside JVM
processes on Wayland.

## What changed

In `src/plugins/gtk/libdecor-gtk.c`, the following check is disabled:

```c
// Original:
if (getpid () != gettid ())
    return NULL;

// Patched:
if (0)
    return NULL;
```

## Build instructions

### Prerequisites (Arch Linux x86_64)

```
sudo pacman -S --needed meson ninja gcc pkg-config wayland-protocols gtk3 cairo pango dbus
```

### Build x86_64 (native)

```
bash build-x86_64.sh
```

Output: `build-x86_64/src/plugins/gtk/libdecor-gtk.so`

### Build aarch64 (cross-compile via Docker)

Requires Docker and QEMU user-mode emulation:

```
sudo pacman -S --needed docker qemu-user-static qemu-user-static-binfmt
sudo systemctl restart systemd-binfmt
```

Then:

```
bash build-aarch64.sh
```

Output: `build-aarch64/src/plugins/gtk/libdecor-gtk.so`

### Installing built plugins into the mod

Copy the outputs to the mod resources:

```
cp build-x86_64/src/plugins/gtk/libdecor-gtk.so ../src/main/resources/natives/linux-x86_64/libdecor-gtk.so
cp build-aarch64/src/plugins/gtk/libdecor-gtk.so ../src/main/resources/natives/linux-aarch64/libdecor-gtk.so
```
