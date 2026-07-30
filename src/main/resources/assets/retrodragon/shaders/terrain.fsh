#version 120
#extension GL_ARB_shader_texture_lod : enable

// Fixes the two things that make mipmaps unusable on beta's atlas.
//
// 1. LOD SELECTION. GL derives the mip level from the UV derivative between neighbouring pixels.
//    Beta packs every block texture into one 16x16-tile atlas, so at a block-face edge adjacent
//    pixels address different tiles, the derivative explodes, and GL picks the coarsest level --
//    a texel averaged across unrelated tiles. That is the one-pixel wrong-coloured line on the edge
//    of every block. Here the LOD is computed from EYE-SPACE POSITION instead, which is continuous
//    across faces, so it stays correct at every seam.
//
// 2. TILE BLEEDING. Even with a sane LOD, a bilinear tap near a tile border reaches into the
//    neighbouring tile. The sample UV is clamped to stay a half-texel (widened per mip level)
//    inside the owning tile.
//
// NEITHER assumes beta's numbers. The grid is described by tileTexels (the pitch) against atlasSize,
// because a content API can change both: RetroAPI grows the sheet around 16-texel tiles, so a 512
// atlas is 32 tiles per axis. tileTexels == 0 says the atlas is STITCHED (StationAPI) -- sprites at
// arbitrary positions and sizes -- and everything tile-derived switches off, leaving one nearest tap
// at level 0. That is vanilla's look, and the only sampling that is correct when the sprite bounds
// are unknown.

uniform sampler2D atlas;
uniform vec2 atlasSize;    // texels, e.g. (256, 256)
uniform float tileTexels;  // grid pitch in texels (16 for beta); 0 when the atlas is stitched
uniform float maxLod;
uniform float rgss;        // >0.5 enables rotated-grid supersampling
uniform int fogMode;       // 0 linear, 1 exp, 2 exp2 -- GLSL 120 gl_Fog has no mode member

varying vec2 vUv;
varying vec4 vColor;
varying vec3 vEye;

// Sample clamped into the owning tile, so a tap near a tile border cannot reach the next texture.
vec4 tapTile(vec2 uv, vec2 lo, vec2 hi, float lod) {
    vec2 clamped = clamp(uv, lo, hi);
#ifdef GL_ARB_shader_texture_lod
    return texture2DLod(atlas, clamped, lod);
#else
    return texture2D(atlas, clamped);
#endif
}

void main() {
    // World units covered by one pixel, from a coordinate that does not jump between faces.
    float density = max(length(dFdx(vEye)), length(dFdy(vEye)));

    // Stitched atlas: no tile, so no clamp bounds, no mip walk and no supersampling.
    vec2 lo = vec2(0.0);
    vec2 hi = vec2(1.0);
    vec2 fp = vec2(0.0);
    float lod = 0.0;

    if (tileTexels > 0.0) {
        vec2 tileSize = vec2(tileTexels) / atlasSize;
        vec2 tileOrigin = floor(vUv / tileSize) * tileSize;
        // One block is one tile across, so texels-per-pixel is density * texels-per-tile.
        float texelsPerPixel = density * tileTexels;
        lod = clamp(log2(max(texelsPerPixel, 1e-6)), 0.0, maxLod);
        // Widen the inset with the mip level: at level L a texel is 2^L level-0 texels across.
        vec2 inset = (exp2(lod) * 0.5) / atlasSize;
        lo = tileOrigin + inset;
        hi = tileOrigin + tileSize - inset;
        // Pixel footprint in UV, bounded by the mip texel actually being sampled.
        //
        // density is ISOTROPIC -- one max() over both screen derivatives -- so on a surface seen at a
        // grazing angle it over-estimates the footprint without bound, while lod stops climbing at
        // maxLod. Past that point the four RGSS taps are offset by multiple TILES, every one of them
        // clamps to a corner of lo/hi, and the surface reads as a flat wrong-coloured sliver instead
        // of its own texture. Capping fp at exp2(lod) texels keeps the taps inside the texel the LOD
        // already chose; it is exactly a no-op wherever lod is not saturated, since there
        // exp2(lod) == texelsPerPixel by construction.
        fp = min(vec2(density) * tileSize, exp2(lod) / atlasSize);
    }

    vec4 texel;
    if (rgss > 0.5) {
        // Rotated-grid supersampling: four taps on a grid rotated off the pixel axes, which
        // resolves near-horizontal and near-vertical texture detail far better than an axis-aligned
        // 2x2 box. Sampling one mip level sharper than the isotropic choice, since averaging four
        // taps supplies the filtering the extra level would otherwise have blurred in.
        float sharpLod = max(lod - 1.0, 0.0);
        vec4 t0 = tapTile(vUv + fp * vec2(0.125, 0.375), lo, hi, sharpLod);
        vec4 t1 = tapTile(vUv + fp * vec2(0.375, -0.125), lo, hi, sharpLod);
        vec4 t2 = tapTile(vUv + fp * vec2(-0.125, -0.375), lo, hi, sharpLod);
        vec4 t3 = tapTile(vUv + fp * vec2(-0.375, 0.125), lo, hi, sharpLod);
        // Average in PREMULTIPLIED alpha. Beta stores black RGB under its transparent texels, so a
        // straight RGBA mean lets a tap that lands off the edge of a cutout drag the colour toward
        // black while the averaged alpha still clears the 0.5 alpha test -- a black fringe around
        // tall grass, ferns and cacti. Weighting each tap's colour by its own alpha makes fully
        // transparent taps contribute nothing but their (zero) coverage.
        float wsum = t0.a + t1.a + t2.a + t3.a;
        vec3 rgb = (t0.rgb * t0.a + t1.rgb * t1.a + t2.rgb * t2.a + t3.rgb * t3.a)
                 / max(wsum, 1e-4);
        texel = vec4(rgb, wsum * 0.25);
    } else {
        texel = tapTile(vUv, lo, hi, lod);
    }

    vec4 color = texel * vColor;

    // Reproduce beta's fixed-function fog. gl_Fog is still fed by beta's own glFog* calls; only
    // the MODE has to be passed in, because GLSL 120 does not expose it. Beta uses EXP underwater
    // (density 0.1) and in lava (density 2.0), LINEAR everywhere else -- treating all three as
    // linear got water and lava visibly wrong.
    float fog;
    if (fogMode == 1) {
        fog = exp(-gl_Fog.density * gl_FogFragCoord);
    } else if (fogMode == 2) {
        float fd = gl_Fog.density * gl_FogFragCoord;
        fog = exp(-fd * fd);
    } else {
        fog = (gl_Fog.end - gl_FogFragCoord) * gl_Fog.scale;
    }
    fog = clamp(fog, 0.0, 1.0);
    gl_FragColor = vec4(mix(gl_Fog.color.rgb, color.rgb, fog), color.a);
}
