# Links webgpu_dawn.dll for Windows x64 from jWebGPU's own static Dawn.
#
# WHY THIS EXISTS
#
# The FFM bindings in com.periut.webgpu resolve wgpu* through SymbolLookup, which on Windows can
# only find symbols in the PE export table. None of jWebGPU's three published Windows natives put
# the C API there -- the JNI build exports 1423 JNI stubs, the FFM build exports jParser's own
# hashed wrappers (n1001394613, ...), and the C/teavm build exports teavm wrappers. All three
# statically link Dawn and export none of it, because MSVC exports nothing that is not explicitly
# marked. The Linux .so and macOS .dylib export all 263 by default ELF/Mach-O visibility, which is
# the only reason those platforms work at all.
#
# So this takes the static Dawn that jWebGPU links -- webgpu_dawn.lib, shipped inside the
# webgpu-desktop-c-dawn_windows_x64 artifact -- and links it into a DLL that DOES export the C API,
# using a .def generated from jWebGPU's own webgpu.h.
#
# Doing it this way rather than building Dawn from source buys three things:
#
#   * SIZE. This is jWebGPU's lean D3D12/HLSL-only Dawn (~5.5 MB), not a kitchen-sink build. The
#     third-party build-dawn DLL this replaces is 26.7 MB because it also carries Tint's SPIR-V and
#     GLSL shader-translation paths and full RTTI, none of which a D3D12/HLSL target ever executes.
#     It is not a second backend: both Windows builds are D3D-only, and neither has a Vulkan or
#     OpenGL backend in it.
#   * EXACT ABI. Same Dawn revision as the Linux and macOS natives, so the generated bindings match
#     on every platform and the 0x0005xxxx feature-number skew that DawnFeatures existed to paper
#     over does not arise.
#   * NO DAWN TOOLCHAIN. No depot_tools, no gn, no hour-long compile -- just a link step. The 288 MB
#     static lib is a build-time input for whoever runs this, never a runtime dependency.
#
# REQUIREMENTS
#   * MSVC link.exe on PATH -- run from a "x64 Native Tools Command Prompt for VS", or let
#     .github/workflows/dawn-windows.yml do it (windows-latest already has VS2022).
#   * Network access, to fetch the artifact from Maven Central.
#
# USAGE
#   ./build.ps1                        # uses the pinned version below
#   ./build.ps1 -DawnVersion 0.3.5     # after a jWebGPU bump
#
# The DLL lands in out/webgpu_dawn.dll and its SHA-256 is printed -- that is what goes into
# dawn_windows_sha256 in gradle.properties.

param(
    # Keep in step with dawn_version in gradle.properties: the whole point is that Windows links the
    # same Dawn the other platforms load.
    [string]$DawnVersion = '0.3.4',
    [string]$OutDir = "$PSScriptRoot\out"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$artifact = "webgpu-desktop-c-dawn_windows_x64"
$jarUrl = "https://repo1.maven.org/maven2/com/github/xpenatan/jWebGPU/$artifact/$DawnVersion/$artifact-$DawnVersion.jar"

$work = Join-Path $OutDir 'work'
New-Item -ItemType Directory -Force -Path $work | Out-Null

# ---------------------------------------------------------------------------------------------
# 1. Fetch the artifact carrying the static Dawn and the header it was built from.
# ---------------------------------------------------------------------------------------------
$jar = Join-Path $work "$artifact-$DawnVersion.jar"
if (Test-Path $jar) {
    Write-Host "Using cached $jar"
} else {
    Write-Host "Downloading $jarUrl"
    # ~37 MB. Invoke-WebRequest rather than a raw stream copy so a proxy or a 404 surfaces as a
    # clear error instead of a truncated file.
    Invoke-WebRequest -Uri $jarUrl -OutFile "$jar.part" -UseBasicParsing
    Move-Item -Force "$jar.part" $jar
}

$extract = Join-Path $work 'extract'
if (Test-Path $extract) { Remove-Item -Recurse -Force $extract }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($jar, $extract)

$base = Join-Path $extract 'external_cpp\jparser\jwebgpu\native\windows_x64'
$staticLib = Join-Path $base 'md\deps\webgpu_dawn.lib'
$header = Join-Path $base 'include\dawn\webgpu.h'
foreach ($required in @($staticLib, $header)) {
    if (-not (Test-Path $required)) {
        throw "Expected $required inside the artifact. jWebGPU may have changed its layout; check the jar contents."
    }
}
Write-Host ("static Dawn : {0:N0} bytes" -f (Get-Item $staticLib).Length)

# ---------------------------------------------------------------------------------------------
# 2. Generate the .def from the header's own declarations.
#
# Every C API entry point is declared `WGPU_EXPORT <ret> wgpuXxx(...) WGPU_FUNCTION_ATTRIBUTE;`, so
# the export list is derived rather than maintained by hand -- a hand-written list silently rots
# the moment Dawn adds a function. Validated against the Linux .so: the names this produces are
# exactly the 263 symbols that native exports, no more and no less.
# ---------------------------------------------------------------------------------------------
$headerText = Get-Content $header -Raw
$names = [regex]::Matches($headerText, 'WGPU_EXPORT\s[^;]*?\b(wgpu[A-Za-z0-9_]+)\s*\(') |
    ForEach-Object { $_.Groups[1].Value } |
    Sort-Object -Unique
if ($names.Count -lt 200) {
    throw "Only $($names.Count) exports found in $header -- expected ~263. The declaration form may have changed."
}
$def = Join-Path $work 'webgpu_dawn.def'
$lines = @('; Generated by natives/dawn-windows/build.ps1 -- do not edit.', 'EXPORTS') +
    ($names | ForEach-Object { "    $_" })
Set-Content -Path $def -Value $lines -Encoding ascii
Write-Host "exports     : $($names.Count) (from webgpu.h)"

# ---------------------------------------------------------------------------------------------
# 3. Link.
#
# /OPT:REF discards everything the exported set does not reach, which is what keeps this near
# jWebGPU's own size instead of pulling in the whole archive. The system libraries are Dawn's own
# Windows dependencies; d3d12 and dxgi are loaded dynamically by Dawn at runtime but the D3D
# helpers it links against still need the import libs.
# ---------------------------------------------------------------------------------------------
if (-not (Get-Command link.exe -ErrorAction SilentlyContinue)) {
    # Bootstrap the VS environment rather than demanding a Native Tools prompt. vswhere ships with
    # every VS install (and is present on GitHub's windows-latest), so this works from a plain shell
    # locally and lets the CI job skip a third-party setup action entirely.
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path $vswhere)) {
        throw "link.exe is not on PATH and vswhere.exe was not found. Install VS2022 Build Tools (the 'Desktop development with C++' workload), or run from an x64 Native Tools Command Prompt."
    }
    $install = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $install) { throw "vswhere found no VS install with the x64 C++ tools." }
    $vcvars = Join-Path $install 'VC\Auxiliary\Build\vcvars64.bat'
    if (-not (Test-Path $vcvars)) { throw "Missing $vcvars" }
    Write-Host "Importing MSVC environment from $vcvars"
    # `set` after vcvars64 dumps the environment it built; copying it in gives this process link.exe,
    # dumpbin.exe and the x64 system library paths.
    cmd /c "`"$vcvars`" >nul 2>&1 && set" | ForEach-Object {
        if ($_ -match '^([^=]+)=(.*)$') { Set-Item -Path "env:$($matches[1])" -Value $matches[2] }
    }
    if (-not (Get-Command link.exe -ErrorAction SilentlyContinue)) {
        throw "Imported the VS environment but link.exe is still not on PATH."
    }
}
$dll = Join-Path $OutDir 'webgpu_dawn.dll'
$linkArgs = @(
    '/DLL', '/NOLOGO', '/MACHINE:X64',
    "/DEF:$def",
    "/OUT:$dll",
    "/IMPLIB:$(Join-Path $work 'webgpu_dawn.lib')",
    '/OPT:REF', '/OPT:ICF',
    # Release CRT, matching how the static lib was built (the artifact path is md/, i.e. /MD).
    '/NODEFAULTLIB:libcmt.lib',
    $staticLib,
    'd3d12.lib', 'dxgi.lib', 'dxguid.lib', 'd3d11.lib',
    'ole32.lib', 'oleaut32.lib', 'advapi32.lib', 'user32.lib', 'gdi32.lib',
    'shell32.lib', 'shlwapi.lib', 'version.lib', 'propsys.lib',
    'kernel32.lib', 'winmm.lib', 'ws2_32.lib', 'bcrypt.lib', 'userenv.lib', 'ntdll.lib'
)
Write-Host "Linking $dll"
& link.exe @linkArgs
if ($LASTEXITCODE -ne 0) {
    throw "link.exe failed with exit code $LASTEXITCODE. Unresolved externals usually mean a missing system .lib above."
}

# ---------------------------------------------------------------------------------------------
# 4. Verify the C API is actually in the export table.
#
# The whole point of this script. A DLL that links cleanly but exports nothing fails at runtime in
# exactly the way jWebGPU's does -- "Symbol not found: wgpuCreateInstance" on the first call -- and
# that is far too late to find out. Parsed here rather than shelled out to dumpbin so the check
# needs no VS environment and runs the same locally and in CI.
# ---------------------------------------------------------------------------------------------
function Get-PeExportNames {
    param([string]$Path)
    $b = [System.IO.File]::ReadAllBytes($Path)
    $peOff = [BitConverter]::ToUInt32($b, 0x3C)
    $coff = $peOff + 4
    $numSections = [BitConverter]::ToUInt16($b, $coff + 2)
    $optSize = [BitConverter]::ToUInt16($b, $coff + 16)
    $opt = $coff + 20
    $magic = [BitConverter]::ToUInt16($b, $opt)
    $dataDirOff = if ($magic -eq 0x20b) { $opt + 112 } else { $opt + 96 }
    $exportRva = [BitConverter]::ToUInt32($b, $dataDirOff)
    if ($exportRva -eq 0) { return @() }

    $sections = @()
    $secOff = $opt + $optSize
    for ($i = 0; $i -lt $numSections; $i++) {
        $s = $secOff + ($i * 40)
        $sections += [pscustomobject]@{
            VirtualSize = [BitConverter]::ToUInt32($b, $s + 8)
            VirtualAddress = [BitConverter]::ToUInt32($b, $s + 12)
            RawSize = [BitConverter]::ToUInt32($b, $s + 16)
            RawPointer = [BitConverter]::ToUInt32($b, $s + 20)
        }
    }
    $toOff = {
        param($rva)
        foreach ($s in $sections) {
            $size = [Math]::Max($s.VirtualSize, $s.RawSize)
            if ($rva -ge $s.VirtualAddress -and $rva -lt ($s.VirtualAddress + $size)) {
                return $s.RawPointer + ($rva - $s.VirtualAddress)
            }
        }
        throw "RVA $rva is not inside any section"
    }
    $ed = & $toOff $exportRva
    $nameCount = [BitConverter]::ToUInt32($b, $ed + 24)
    $namesOff = & $toOff ([BitConverter]::ToUInt32($b, $ed + 32))
    $out = New-Object System.Collections.Generic.List[string]
    for ($i = 0; $i -lt $nameCount; $i++) {
        $o = & $toOff ([BitConverter]::ToUInt32($b, $namesOff + $i * 4))
        $e = $o; while ($b[$e] -ne 0) { $e++ }
        $out.Add([System.Text.Encoding]::ASCII.GetString($b, $o, $e - $o))
    }
    return $out
}

$exported = Get-PeExportNames -Path $dll
$exportedWgpu = @($exported | Where-Object { $_ -like 'wgpu*' })
Write-Host "verified    : $($exportedWgpu.Count) wgpu* symbols in the export table"
if ($exportedWgpu.Count -ne $names.Count) {
    throw "Expected $($names.Count) wgpu* exports but the DLL has $($exportedWgpu.Count). The .def did not fully take effect."
}
if ($exportedWgpu -notcontains 'wgpuCreateInstance') {
    throw "wgpuCreateInstance is not exported -- the FFM bindings would fail on their first call."
}

# ---------------------------------------------------------------------------------------------
# 5. Report what to paste into gradle.properties.
# ---------------------------------------------------------------------------------------------
$size = (Get-Item $dll).Length
$sha = (Get-FileHash $dll -Algorithm SHA256).Hash.ToLower()
Write-Host ''
Write-Host "webgpu_dawn.dll : $('{0:N0}' -f $size) bytes"
Write-Host "sha256          : $sha"
Write-Host ''
Write-Host 'Put this in gradle.properties:'
Write-Host "  dawn_windows_sha256 = $sha"
