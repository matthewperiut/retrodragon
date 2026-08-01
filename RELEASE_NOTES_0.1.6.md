# RetroDragon 0.1.6

## New: a numeric frame limit in the runtime API

`com.periut.retrodragon.api.RetroSettings`, added in 0.1.5 for RGSS and terrain mipmaps, gets a third
setting:

```
RetroSettings.getFrameLimit() / setFrameLimit(int)
```

Where the existing settings are booleans, this one is a plain `int`: the frame rate, in frames per
second, the renderer should hold. `0` means no numeric cap (`RetroSettings.NO_FRAME_LIMIT`), the same
convention the bench harness already uses for vanilla's own frame-limit field. Same reflection-friendly
shape as the rest of the class: stable name, public static method, a primitive in and out, nothing that
requires a compile-time dependency on RetroDragon.

## Fixed: a numeric FPS limit had nothing to act on

Reported from play: setting a numeric FPS limit did nothing, while vsync and unlimited both worked.
That tracked, once traced through. `FramePacing`, the class that decides how a frame is paced, only
ever understood a boolean (vsync forced on or off, via `Display.setVSyncEnabled`) and the vanilla
"Performance" option's three fixed tiers. There was no numeric target anywhere for "cap me at 120" to
reach.

`FramePacing` now also carries the numeric limit from `RetroSettings.setFrameLimit`, and the swapchain
present-mode choice on WebGPU changes with it: a numeric target picks Mailbox, the same as Max FPS,
rather than the vsync-locked Fifo the three-tier setting would otherwise choose. That distinction
matters for a target above the display's refresh rate. Fifo blocks a present at the display's own
rate, so a request for 240 fps on a 60 Hz panel could never be reached through it no matter how
precisely anything slept afterward; Mailbox lets the renderer run free and the sleep throttle it to
the exact number instead, correct whether the target sits above, at, or below the display's refresh
rate.

vsync and a numeric limit are independent settings, and a caller that sets both gets both: vsync still
decides whether presentation waits for the display, the numeric limit still bounds the rate on top of
that, the same way the existing Power Saver tier already combines a fixed 60 fps sleep with vsync. With
neither set, the three-tier "Performance" option decides exactly as it always has.

## Fixed: `Display.sync(int)`, the classic LWJGL 2 frame limiter, was disconnected from all of this

RetroDragon's LWJGL 2 compatible `Display` shim already had a real, working `sync(int)`: the frame
limiter mods written for vanilla b1.7.3 already call, once a frame, to cap their rate. Its sleep
algorithm was correct and complete on its own, but it ran against its own private clock, invisible to
everything above. A mod calling it never told the WebGPU present-mode choice a numeric target was even
in play, so on a display whose refresh rate sat below the requested number, the same Fifo-blocks-first
problem applied. On the GL backend, nothing analogous ran at all outside the vanilla three-tier option.

`Display.sync(int)` now feeds the same numeric limit `RetroSettings.setFrameLimit` does, so a mod using
either entry point lands on identical behavior. It no longer sleeps itself: the actual wait now happens
once a frame, centrally, at the point each backend already presents (`WebGpuRenderer.endFrame` on
WebGPU, `Display`'s own swap points on GL, both now reached unconditionally every frame). Running two
independent sleep loops toward the same target in the same frame does not cancel out; started at
different moments they drift out of phase, and each one's wait stacks on the other's rather than
overlapping with it, which would have quietly landed the visible rate at roughly half of whatever was
requested. Centralizing the sleep is what keeps that from happening while still making an existing
mod's call to `Display.sync(fps)` take effect.

The GL backend now paces frames from `FramePacing` as well, which it never did before this release.
That is scoped narrowly: it only ever applies a numeric limit. The three-tier setting's own Power Saver
sleep stays exactly where it already lived, in vanilla's own render loop, rather than being duplicated
here.

## Notes for anyone verifying this build

Both `./gradlew build` and `./gradlew selfChecks` pass, including a new `framePacingTest` alongside the
existing `retroSettingsTest`. Together they check every precedence rule described above as plain
arithmetic (no GPU, no window, no running game required): the numeric limit's priority over vsync and
the three-tier setting, that the present-mode choice and the "does this uncap the renderer" question
always agree with each other, and a bounded real timing check that `FramePacing.await()` actually
sleeps toward its target rather than returning immediately or stalling.

What none of that can confirm is what a held frame rate looks like on screen. Whether a numeric cap set
through either entry point actually lands at the requested number, on both backends, at targets above
and below the display's refresh rate, needs a human running the game with a frame counter. So does
confirming that an existing mod calling `Display.sync(int)` behaves the way it always has, just now
actually capping the rate instead of quietly doing nothing.
