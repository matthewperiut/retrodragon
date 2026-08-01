# RetroDragon 0.1.5

## New: a runtime API for RGSS and terrain mipmaps

RGSS and terrain mipmaps used to be launch-time only, set from `-Dretroperf.rgss` and
`-Dretroperf.mipmap` and fixed for the whole run. A new class, `com.periut.retrodragon.api.RetroSettings`,
lets another mod turn either one on or off while the game is running:

```
RetroSettings.isRgss() / setRgss(boolean)
RetroSettings.isMipmap() / setMipmap(boolean)
```

It is built to be called reflectively, without a compile-time dependency on RetroDragon: a stable
class name, public static methods, and only `boolean` in and out of every method.

The two launch-time properties keep working exactly as documented; they are now the seed for the
current value rather than the value itself, so `-Dretroperf.rgss=false` and `-Dretroperf.mipmap=false`
still behave the same as before.

RGSS is a pure shader setting, so flipping it is instant on both the GL and WebGPU backend, on the
next frame drawn. Terrain mipmaps are not symmetric: turning them off is just as instant (the shader
clamps its sampled level to 0), but turning them on has to rebuild and re-upload the block atlas's mip
chain, since a chain that was never built cannot be sampled. That reupload reuses the same
`TextureManager.reload()` path a texture pack switch already takes, so both backends are covered by
one call, not a second uploader written just for this.

Calling `setMipmap(true)` from the render thread runs that reupload synchronously before the call
returns. Calling it from any other thread still updates `isMipmap()` immediately, but the actual
reupload is deferred to the render thread and applied automatically on the very next frame; nothing
needs to poll for it. Setting a value to what it already is is a no-op, which matters most for
mipmaps, since that reupload is the expensive direction.

## Fixed: `Display.setVSyncEnabled` did nothing on the default backend

RetroDragon ships an LWJGL 2 compatible `org.lwjgl.opengl.Display` shim with the classic
`setVSyncEnabled(boolean)` call, but nothing wired it to anything that mattered under WebGPU, which is
the default backend. WebGPU has no GL swap interval to set; it picks its present mode from the
existing three tier "Performance" video option instead. A mod calling `Display.setVSyncEnabled` on the
default backend was therefore a silent no-op: the call succeeded, nothing on screen changed.

`setVSyncEnabled` now also sets an override on the frame pacer that the WebGPU present mode and frame
timing both read, so the call means the same thing on both backends. Once set it takes priority over
the "Performance" option's own mapping until called again; nothing calls it internally, so a run that
never uses this API behaves exactly as before.

## Notes for anyone verifying this build

Both changes are logic that a human still needs to see on screen: RGSS and mipmap toggling render
correctly, and vsync actually engages or disengages under WebGPU. Neither could be confirmed visually
in the environment this release was built in; what was verified there is described in full in the
commit itself, including a small assertion based self-check runnable with `./gradlew retroSettingsTest`.
