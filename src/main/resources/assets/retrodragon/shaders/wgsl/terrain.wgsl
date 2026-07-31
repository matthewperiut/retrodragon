// Terrain program: the WGSL port of terrain.vsh/terrain.fsh.
//
// Same uniform block as fixedfunc.wgsl -- it has to be, since both share one bind group layout and
// one pipeline layout, which is what lets a bind group be reused across every pipeline. The three
// extra parameters ride in the w components of the light vectors, which are padding in the lighting
// maths (a directional light's w is 0, the ambient's alpha is unused).
//
// It fixes the two things that make mipmaps unusable on beta's atlas:
//
//  1. LOD SELECTION. A GPU derives the mip level from the UV derivative between neighbouring pixels.
//     Beta packs every block texture into one 16x16-tile atlas, so at a block-face edge adjacent
//     pixels address different tiles, the derivative explodes, and the coarsest level is chosen -- a
//     texel averaged across unrelated tiles. That is the one-pixel wrong-coloured line on the edge
//     of every block. The LOD is computed from EYE-SPACE POSITION instead, which is continuous
//     across faces and therefore correct at every seam.
//
//  2. TILE BLEEDING. Even with a sane LOD, a filtered tap near a tile border reaches into the
//     neighbouring tile. Every sample UV is clamped to stay a half-texel -- widened per mip level --
//     inside the owning tile.
//
// Neither assumes beta's numbers. The grid is described by a PITCH in texels against the atlas size,
// since a content API can change both: RetroAPI grows the sheet around 16-texel tiles (a 512 atlas is
// 32 tiles per axis), and StationAPI replaces it with a stitched atlas that has no grid at all. A
// pitch of 0 means stitched, and every tile-derived step switches off -- one nearest tap at level 0,
// which is vanilla's look and the only correct sampling when the sprite bounds are unknown.

struct Uniforms {
    modelView   : mat4x4<f32>,
    projection  : mat4x4<f32>,
    colorModulator : vec4<f32>,
    fogColor    : vec4<f32>,
    fogParams   : vec4<f32>,
    flags       : vec4<f32>,
    // xyz light direction, w = block atlas width in texels
    lightDir0   : vec4<f32>,
    // xyz light direction, w = maximum mip level (0 disables mipmapping)
    lightDir1   : vec4<f32>,
    // rgb ambient, w = 1 to enable rotated-grid supersampling
    lightAmbient: vec4<f32>,
    // xyz vertex-attribute flags, w = the atlas's grid pitch in texels (0 = stitched, no grid)
    vertexFlags : vec4<f32>,
};

@group(0) @binding(0) var<uniform> u : Uniforms;
@group(0) @binding(1) var texSampler : sampler;
@group(0) @binding(2) var texture    : texture_2d<f32>;

// NO NORMAL. Terrain lighting is baked into the vertex colour by beta's own block renderer, so the
// normal slot this shader used to declare was fetched for every terrain vertex and never read. It
// is dropped here rather than left in as documentation, because a declared attribute is a real
// binding: the pipeline has to describe it, the vertex has to carry it, and the fetch costs
// bandwidth on the single largest stream of vertices the game produces.
//
// Dropping it is also what frees the layout to be COMPACT. `uv` is declared vec2<f32> and stays
// that way whether the buffer holds two floats or two unorm16s -- a vertex format converts on fetch,
// so both layouts drive this shader unchanged. See TerrainVertex.
// spriteTexels is present only when a stitched atlas is installed -- see TerrainVertex.spriteClamp.
// The pipeline declares it or does not; a shader that reads an undeclared location is invalid, so the
// program is compiled with SPRITE_CLAMP substituted for the two lines that touch it. See
// FixedFunctionPipelines.source.
struct VertexIn {
    @location(0) position : vec3<f32>,
    @location(1) uv       : vec2<f32>,
    @location(2) color    : vec4<f32>,
    //@SPRITE_BEGIN
    @location(3) spriteTexels : vec4<u32>,
    //@SPRITE_END
};

struct VertexOut {
    @builtin(position) clipPosition : vec4<f32>,
    @location(0) uv       : vec2<f32>,
    @location(1) color    : vec4<f32>,
    @location(2) fogDepth : f32,
    // Eye-space position, carried so the fragment stage can take a derivative that does NOT jump
    // between atlas tiles. This is the whole trick behind correct LOD selection.
    @location(3) eye      : vec3<f32>,
    // The owning sprite's edge length in texels. Flat: it is a per-quad constant, and interpolating
    // it across a face would put a fractional pitch in the middle of every tile.
    @location(4) @interpolate(flat) spriteTexels : f32,
};

@vertex
fn vs_main(in : VertexIn) -> VertexOut {
    var out : VertexOut;
    let eye = u.modelView * vec4<f32>(in.position, 1.0);
    var clip = u.projection * eye;
    // GL clips to -w..w, WebGPU to 0..w. Beta's own projection matrices are left untouched.
    clip.z = (clip.z + clip.w) * 0.5;

    out.clipPosition = clip;
    out.uv = select(vec2<f32>(0.0, 0.0), in.uv, u.vertexFlags.z > 0.5);
    out.eye = eye.xyz;
    out.fogDepth = length(eye.xyz);
    out.color = in.color * u.colorModulator;
    // 0 when the layout does not carry it, which reads as "no sprite" and leaves the uniform pitch in
    // charge -- the grid case, and the honest fallback for a sprite the table could not place.
    out.spriteTexels = 0.0;
    //@SPRITE_BEGIN
    out.spriteTexels = f32(in.spriteTexels.x);
    //@SPRITE_END
    return out;
}

/// A tap clamped into the owning tile, so filtering near a border cannot reach the next texture.
fn tapTile(uv : vec2<f32>, lo : vec2<f32>, hi : vec2<f32>, lod : f32) -> vec4<f32> {
    return textureSampleLevel(texture, texSampler, clamp(uv, lo, hi), lod);
}

@fragment
fn fs_main(in : VertexOut) -> @location(0) vec4<f32> {
    let atlasTexels = u.lightDir0.w;
    let maxLod = u.lightDir1.w;
    let rgss = u.lightAmbient.w;
    // A stitched sheet has no single pitch, so each vertex brought its own sprite's edge length.
    // TextureStitcher places a sprite of size S at a multiple of S, so floor(uv / S) * S below finds
    // its origin exactly as it finds a tile's -- the rest of this function does not know the
    // difference. Zero means "not carried, or unplaceable", and the uniform grid pitch stands.
    var tileTexels = u.vertexFlags.w;
    if (in.spriteTexels > 0.0) {
        tileTexels = in.spriteTexels;
    }

    // World units covered by one pixel, from a coordinate that does not jump between faces.
    let density = max(length(dpdx(in.eye)), length(dpdy(in.eye)));

    // Stitched atlas: no tile, so no clamp bounds, no mip walk and no supersampling.
    var lo = vec2<f32>(0.0);
    var hi = vec2<f32>(1.0);
    var fp = vec2<f32>(0.0);
    var lod = 0.0;

    if (tileTexels > 0.0) {
        let tileSize = tileTexels / atlasTexels;
        let tileOrigin = floor(in.uv / tileSize) * tileSize;
        // One block is one tile across, so texels-per-pixel is density * texels-per-tile.
        let texelsPerPixel = density * tileTexels;
        lod = clamp(log2(max(texelsPerPixel, 1e-6)), 0.0, maxLod);
        // Widen the inset with the mip level: at level L a texel is 2^L level-0 texels across.
        let inset = vec2<f32>((exp2(lod) * 0.5) / atlasTexels);
        lo = tileOrigin + inset;
        hi = tileOrigin + vec2<f32>(tileSize) - inset;
        // Pixel footprint in UV, bounded by the mip texel actually being sampled.
        //
        // density is ISOTROPIC -- one max() over both screen derivatives -- so on a surface seen at a
        // grazing angle it over-estimates the footprint without bound, while lod stops climbing at
        // maxLod. Past that point the four RGSS taps are offset by multiple TILES, every one of them
        // clamps to a corner of lo/hi, and the surface reads as a flat wrong-coloured sliver instead
        // of its own texture. Capping fp at exp2(lod) texels keeps the taps inside the texel the LOD
        // already chose; it is exactly a no-op wherever lod is not saturated, since there
        // exp2(lod) == texelsPerPixel by construction.
        fp = vec2<f32>(min(density * tileSize, exp2(lod) / atlasTexels));
    }

    var texel : vec4<f32>;
    if (rgss > 0.5) {
        // Rotated-grid supersampling: four taps on a grid rotated off the pixel axes, which
        // resolves near-horizontal and near-vertical detail far better than an axis-aligned 2x2
        // box. Sampled one mip level sharper than the isotropic choice, since averaging four taps
        // supplies the filtering that level would otherwise have blurred in.
        let sharpLod = max(lod - 1.0, 0.0);
        let t0 = tapTile(in.uv + fp * vec2<f32>(0.125, 0.375), lo, hi, sharpLod);
        let t1 = tapTile(in.uv + fp * vec2<f32>(0.375, -0.125), lo, hi, sharpLod);
        let t2 = tapTile(in.uv + fp * vec2<f32>(-0.125, -0.375), lo, hi, sharpLod);
        let t3 = tapTile(in.uv + fp * vec2<f32>(-0.375, 0.125), lo, hi, sharpLod);
        // Averaged in PREMULTIPLIED alpha. Beta stores black RGB under its transparent texels, so a
        // straight RGBA mean lets a tap landing off the edge of a cutout drag the colour toward
        // black while the averaged alpha still clears the 0.5 alpha test -- a black fringe around
        // tall grass, ferns and cacti. Weighting each tap by its own alpha makes fully transparent
        // taps contribute nothing but their (zero) coverage.
        let wsum = t0.a + t1.a + t2.a + t3.a;
        let rgb = (t0.rgb * t0.a + t1.rgb * t1.a + t2.rgb * t2.a + t3.rgb * t3.a) / max(wsum, 1e-4);
        texel = vec4<f32>(rgb, wsum * 0.25);
    } else {
        texel = tapTile(in.uv, lo, hi, lod);
    }

    var color = texel * in.color;

    // The alpha test, and the reason there are two terrain pipelines.
    //
    // A fragment shader that CONTAINS a discard cannot have its depth written before it runs, so
    // the driver turns off early depth testing for the whole pipeline -- every fragment is shaded
    // and only then rejected. Beta's terrain pass has the alpha test on throughout, for the sake of
    // leaves, grass and glass, so this discard was disabling early-Z for the entire opaque world.
    //
    // The region between these markers is STRIPPED to build the opaque variant; see
    // FixedFunctionPipelines.source. It is delimited textually rather than switched on a uniform
    // because a uniform does not help: what costs the early-Z is the discard being present in the
    // compiled shader at all, not whether it is reached.
    //@ALPHA_TEST_BEGIN
    if (u.flags.y > 0.5 && color.a <= u.flags.x) {
        discard;
    }
    //@ALPHA_TEST_END

    var fog : f32;
    if (u.fogParams.x < 0.5) {
        fog = (u.fogParams.z - in.fogDepth) * u.fogParams.w;
    } else if (u.fogParams.x < 1.5) {
        fog = exp(-u.fogParams.y * in.fogDepth);
    } else {
        let fd = u.fogParams.y * in.fogDepth;
        fog = exp(-fd * fd);
    }
    fog = clamp(fog, 0.0, 1.0);

    return vec4<f32>(mix(u.fogColor.rgb, color.rgb, fog), color.a);
}
