# webgpu_dawn.dll for Windows

Windows is the one platform whose Dawn this repository has to produce itself. Everything here
exists to link a DLL that exports the WebGPU C API, because nothing published does.

## Why

`com.periut.webgpu` is jextract output: it resolves `wgpuCreateInstance` and 262 siblings through
`SymbolLookup.loaderLookup()`. On Windows that can only find symbols in the PE **export table**.

jWebGPU publishes three Windows natives and none of them exports the C API:

| artifact | DLL | `wgpu*` exports | what it exports instead |
| --- | --- | --- | --- |
| `webgpu-desktop-jni-dawn_windows_x64` | 5.56 MB | 0 | 1423 JNI stubs |
| `webgpu-desktop-ffm-dawn_windows_x64` | 5.52 MB | 0 | jParser hashed wrappers (`n1001394613`, ...) |
| `webgpu-desktop-c-dawn_windows_x64` | 5.54 MB | 0 | teavm/jParser wrappers |

All three link Dawn statically and export none of it, because MSVC exports nothing that is not
marked `__declspec(dllexport)` or listed in a `.def`. The Linux `.so` and macOS `.dylib` export all
263 by default ELF/Mach-O visibility, which is the only reason those platforms work at all — the
FFM path there is riding on symbols that were never deliberately exported.

Nor can the addresses be recovered another way: `jWebGPU64.dll` has `NumberOfSymbols = 0` and no
CodeView entry, so there is no PDB reference, let alone a PDB. The information is not in the file.

## What `build.ps1` does

The `webgpu-desktop-c-dawn_windows_x64` artifact ships `md/deps/webgpu_dawn.lib` — the 288 MB
**static** Dawn that jWebGPU links — and the `webgpu.h` it was built from. So:

1. fetch that artifact from Maven Central,
2. generate a `.def` from the header's `WGPU_EXPORT` declarations,
3. `link.exe /DLL /DEF:... /OPT:REF /OPT:ICF` against the static lib.

The `.def` is generated, never hand-maintained — a hand-written list rots the moment Dawn adds a
function. It was validated against the working Linux native: the 263 names it produces are exactly
the 263 that `libjWebGPU64.so` exports, with no difference in either direction.

This beats building Dawn from source on all three axes that matter here:

- **Size.** It is jWebGPU's lean D3D12/HLSL-only Dawn (~5.5 MB). The third-party `build-dawn` DLL
  it replaces is 26.7 MB: 20.6 MB of `.text` against jWebGPU's 3.85 MB, carrying Tint's SPIR-V and
  GLSL shader-translation paths and full RTTI, none of which a D3D12/HLSL target executes. The
  extra bulk is *not* another backend — both Windows builds are D3D-only. Neither contains a Vulkan
  or OpenGL backend: `vkGetInstanceProcAddr`, `vulkan-1.dll`, `wglCreateContext` and `eglGetDisplay`
  are all absent from both binaries, while `D3D12CreateDevice` and `d3d12.dll` are present in both.
  So `-Dretrogpu.backendType=vulkan` (or `opengl`) finds no adapter on Windows with either DLL.
- **ABI.** Same Dawn revision as the Linux and macOS natives, so the generated bindings are correct
  everywhere and the `0x0005xxxx` feature-number skew does not arise. jWebGPU 0.3.4's own header
  gives `WGPUFeatureName_MultiDrawIndirect = 0x00050034`, matching the bindings exactly.
- **Toolchain.** No depot_tools, no gn, no hour-long compile. Just a link step. The 288 MB static
  lib is a build-time input for whoever runs this, never a runtime dependency for anyone.

## Running it

Normally you do not: run the **Dawn (Windows)** workflow (`.github/workflows/dawn-windows.yml`)
from the Actions tab. It uses `windows-latest`, which already has MSVC, verifies that the result
actually exports the C API, and attaches the DLL to a release.

Locally, from an *x64 Native Tools Command Prompt for VS 2022*:

```powershell
./natives/dawn-windows/build.ps1 -DawnVersion 0.3.4
```

## Switching the build over to it

After the workflow has published a release, edit `gradle.properties`:

```properties
dawn_windows_tag    = dawn-windows-0.3.4
dawn_windows_url    = https://github.com/<owner>/retrodragon/releases/download/dawn-windows-0.3.4/webgpu_dawn.dll
dawn_windows_sha256 = <sha256 from the job summary>
```

and set `DAWN_WINDOWS_TAG` in `WebGPUNatives` to the same tag. `downloadWindowsDawn` handles a bare
`.dll` as well as a `.zip`, so nothing in `build.gradle` changes.

Then, because Windows is now on the same Dawn as everything else:

- delete `src/main/java/com/periut/retrodragon/gpu/DawnFeatures.java`,
- in `FeatureProbe` and `WebGPUSmokeTest`, replace `DawnFeatures.multiDrawIndirect()` with
  `WGPUFeatureName_MultiDrawIndirect()` from the generated bindings,
- drop the `isWindows()` accessor from `WebGPUNatives` if nothing else has started using it.

Verify with `./gradlew smokeTest webgpuWindow featureProbe` on Windows. Expect `backend = D3D12`,
a surface that configures, frames presented with none skipped, and `MultiDrawIndirect ... = true`.

## Licensing

Dawn is BSD-3-Clause. The linked DLL is a redistribution of Dawn and carries its terms; the mod
already ships `LICENSE` and `LICENSE.LGPL` in the jar for its own code.
