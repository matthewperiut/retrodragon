# Why this mod declares LGPL-2.1 as well as MIT

RetroDragon's own code is MIT, © Matthew Periut. See `LICENSE` at the repository root.

The built jar additionally ships two prebuilt Linux binaries under `natives/`:

| binary | origin | licence |
| --- | --- | --- |
| `libcenter.so` | written for this project; uses the Wayland `pointer-warp-v1` protocol description (MIT, © Neal Gompa, Xaver Hugl, Matthias Klumpp, Vlad Zahorodnii) | MIT |
| `libdecor-gtk.so` | **a patched build of the GTK plugin from [libdecor](https://gitlab.freedesktop.org/libdecor/libdecor) v0.2.5** | **LGPL-2.1** |

`libdecor-gtk.so` is the reason `LICENSE.LGPL` exists and why `fabric.mod.json` lists
`LGPL-2.1-only` next to `MIT`. It is not a licence RetroDragon chose; it is one it inherits by
redistributing a derivative of an LGPL work. The patch is described in `README.md` in this
directory: upstream refuses to initialise off the main thread, which is every thread a JVM runs Java
on.

Removing the LGPL declaration would misstate the licence of a binary the jar actually contains. The
only way to drop it honestly is to stop shipping `libdecor-gtk.so`, which costs Wayland users
correct client-side window decorations.

The LGPL applies to that binary, not to RetroDragon. Everything under `src/main/java/com/periut` is
MIT, and so is `src/main/java/org/lwjgl` -- that package name is dictated by b1.7.3's own compiled
bytecode, which calls `org.lwjgl.*` by name, and is not derived from LWJGL's sources.
