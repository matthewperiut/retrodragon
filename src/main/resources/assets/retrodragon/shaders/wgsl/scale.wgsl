// Filtered resample of the world target onto the swapchain. The WebGPU half of render scale.
//
// One shader with a uniform-selected filter rather than one shader per filter. A fullscreen pass is
// bandwidth-bound, not branch-bound, and the branch is uniform across every invocation in the draw,
// so it costs nothing measurable -- while four pipelines would cost four pipeline objects, four bind
// group layouts and a cache to pick between them, all to save a branch that never diverges.

struct Params {
    // xy = source size in texels, zw = 1 / source size.
    srcSize   : vec4<f32>,
    // x = filter mode, y = sharpness (FSR only), zw unused.
    control   : vec4<f32>,
    // The destination rectangle in NDC: xy = centre offset, zw = half-extent. Only integer snapping
    // uses anything other than a full-screen rect; see ScaleBlit for why the snap lives here.
    rect      : vec4<f32>,
};

const MODE_POINT : f32 = 0.0;
const MODE_BILINEAR : f32 = 1.0;
const MODE_BICUBIC : f32 = 2.0;
const MODE_FSR1 : f32 = 3.0;

@group(0) @binding(0) var<uniform> u : Params;
@group(0) @binding(1) var srcSampler : sampler;
@group(0) @binding(2) var srcTexture : texture_2d<f32>;

struct VertexOut {
    @builtin(position) position : vec4<f32>,
    @location(0) uv : vec2<f32>,
};

// A single oversized triangle rather than two triangles. It covers the screen with three vertices
// and no shared edge, so there is no diagonal seam where the two halves meet and half the vertex
// work.
@vertex
fn vs_main(@builtin(vertex_index) index : u32) -> VertexOut {
    var out : VertexOut;
    let uv = vec2<f32>(f32((index << 1u) & 2u), f32(index & 2u));
    out.uv = uv;
    let ndc = uv * vec2<f32>(2.0, -2.0) + vec2<f32>(-1.0, 1.0);
    out.position = vec4<f32>(ndc * u.rect.zw + u.rect.xy, 0.0, 1.0);
    return out;
}

// textureSampleLevel, NOT textureSample. The filter is chosen by an early return per branch, and
// WGSL treats anything after a conditional return as non-uniform control flow -- where an implicit
// LOD is illegal, because the derivative it needs may not exist for every invocation in the quad.
// An explicit level sidesteps that entirely, and costs nothing here: the source has one mip and the
// samplers clamp their LOD to 0, so level 0 is the only thing that was ever going to be read.
fn tap(uv : vec2<f32>) -> vec4<f32> {
    return textureSampleLevel(srcTexture, srcSampler, uv, 0.0);
}

fn luma(c : vec3<f32>) -> f32 {
    return dot(c, vec3<f32>(0.2126, 0.7152, 0.0722));
}

// Catmull-Rom basis, a = -0.5.
fn w0(x : f32) -> f32 { return ((-0.5 * x + 1.0) * x - 0.5) * x; }
fn w1(x : f32) -> f32 { return (1.5 * x - 2.5) * x * x + 1.0; }
fn w2(x : f32) -> f32 { return ((-1.5 * x + 2.0) * x + 0.5) * x; }
fn w3(x : f32) -> f32 { return (0.5 * x - 0.5) * x * x; }

@fragment
fn fs_main(in : VertexOut) -> @location(0) vec4<f32> {
    let size = u.srcSize.xy;
    let rcp = u.srcSize.zw;
    let mode = u.control.x;

    // Point. Snapped to the texel centre in the shader so the result does not depend on the sampler
    // having been created with the matching filter.
    if (mode < MODE_BILINEAR - 0.5) {
        let texel = floor(in.uv * size) + vec2<f32>(0.5, 0.5);
        return tap(texel * rcp);
    }

    let coord = in.uv * size - vec2<f32>(0.5, 0.5);
    let base = floor(coord);
    let f = coord - base;

    if (mode < MODE_BICUBIC - 0.5) {
        // Bilinear, explicit for the same reason as point.
        let c00 = tap((base + vec2<f32>(0.5, 0.5)) * rcp);
        let c10 = tap((base + vec2<f32>(1.5, 0.5)) * rcp);
        let c01 = tap((base + vec2<f32>(0.5, 1.5)) * rcp);
        let c11 = tap((base + vec2<f32>(1.5, 1.5)) * rcp);
        return mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
    }

    if (mode < MODE_FSR1 - 0.5) {
        // Catmull-Rom. The filter that earns its cost above 1.0, where a supersampled frame is being
        // reduced and bilinear discards most of what the extra samples bought.
        var wx : array<f32, 4>;
        var wy : array<f32, 4>;
        wx[0] = w0(f.x); wx[1] = w1(f.x); wx[2] = w2(f.x); wx[3] = w3(f.x);
        wy[0] = w0(f.y); wy[1] = w1(f.y); wy[2] = w2(f.y); wy[3] = w3(f.y);

        var sum = vec4<f32>(0.0);
        for (var j = 0; j < 4; j++) {
            for (var i = 0; i < 4; i++) {
                let at = (base + vec2<f32>(f32(i) - 0.5, f32(j) - 0.5)) * rcp;
                sum += tap(at) * (wx[i] * wy[j]);
            }
        }
        // The negative lobes can push a channel below zero on a high-contrast edge, and a negative
        // colour inverts as soon as anything multiplies by it.
        return vec4<f32>(max(sum.rgb, vec3<f32>(0.0)), 1.0);
    }

    // FSR1: fit a direction to the local gradient and resample ALONG the edge rather than across it,
    // then sharpen with a neighbourhood clamp so it cannot ring. Spatial only, which is exactly why
    // this and not FSR 2/3, XeSS or DLSS -- no motion vectors, no depth, no history.
    let c = (base + vec2<f32>(0.5, 0.5)) * rcp;
    let tl = tap(c + vec2<f32>(-1.0, -1.0) * rcp).rgb;
    let t  = tap(c + vec2<f32>( 0.0, -1.0) * rcp).rgb;
    let tr = tap(c + vec2<f32>( 1.0, -1.0) * rcp).rgb;
    let l  = tap(c + vec2<f32>(-1.0,  0.0) * rcp).rgb;
    let m  = tap(c).rgb;
    let r  = tap(c + vec2<f32>( 1.0,  0.0) * rcp).rgb;
    let bl = tap(c + vec2<f32>(-1.0,  1.0) * rcp).rgb;
    let b  = tap(c + vec2<f32>( 0.0,  1.0) * rcp).rgb;
    let br = tap(c + vec2<f32>( 1.0,  1.0) * rcp).rgb;

    // gradient points ACROSS the edge by definition: it is the direction luma changes fastest, so
    // the edge itself runs perpendicular to it.
    let gradient = vec2<f32>(luma(r) - luma(l), luma(b) - luma(t));
    let edge = length(gradient);

    // Anisotropy goes into the bilinear WEIGHTS, not into a displaced tap.
    //
    // Displacing the tap by a function of f and then using f again as the blend weight applies the
    // fractional position twice, so the effective sample moves faster than the pixel does. On a
    // diagonal that reads as a serrated staircase, as though the interpolation ran backwards.
    // Weight space cannot double count: the taps stay on the texel grid and only their mix changes.
    var w = f;
    if (edge >= 1.0 / 64.0) {
        let dir = gradient / edge;
        // Componentwise, how much each axis lies ACROSS the edge. A vertical edge has a horizontal
        // gradient, so align.x is ~1 and align.y ~0: sharpen in x, stay linear in y.
        let align = dir * dir;
        // Push the fraction toward a step across the edge and leave it linear along the edge. A hard
        // boundary then resolves over a fraction of a destination pixel while the direction the edge
        // runs in stays smoothly interpolated.
        let sharpen = 1.0 + 3.0 * clamp(edge, 0.0, 1.0);
        let stepped = clamp((f - vec2<f32>(0.5, 0.5)) * sharpen + vec2<f32>(0.5, 0.5),
                            vec2<f32>(0.0), vec2<f32>(1.0));
        w = mix(f, stepped, align);
    }

    var result = mix(mix(m, r, w.x), mix(b, br, w.x), w.y);

    let sharpness = u.control.y;
    if (sharpness > 0.0) {
        let lo = min(min(min(tl, t), min(tr, l)), min(min(m, r), min(min(bl, b), br)));
        let hi = max(max(max(tl, t), max(tr, l)), max(max(m, r), max(max(bl, b), br)));
        let blur = (t + l + r + b + m * 4.0) * 0.125;
        result = clamp(result + (result - blur) * sharpness, lo, hi);
    }

    return vec4<f32>(clamp(result, vec3<f32>(0.0), vec3<f32>(1.0)), 1.0);
}
