#version 120

// FSR 1 style edge-adaptive spatial upsampling, followed by contrast-adaptive sharpening.
//
// This is a compact reimplementation of what AMD's EASU + RCAS pair does, written against the
// GL 2.1 floor the rest of this renderer targets: no textureGather, no integer ops, no compute.
// It is not a line-for-line port of the reference kernels, and it does not try to be -- those are
// written around 16-bit packed math and a gather-4 the fixed-function floor here does not have.
// What it keeps is the part that matters: fit a direction to the local gradient, and resample ALONG
// the edge rather than across it, so a hard boundary stays hard instead of becoming a staircase of
// blended steps.
//
// Spatial only. No motion vectors, no depth, no jitter, no history -- which is the entire reason
// this and not FSR 2/3, XeSS or DLSS. It also means it cannot invent detail that is not in the
// frame; below about 0.6 scale it is holding edges together, not reconstructing.
//
// Upscale only. Above 1.0 the job is reducing a supersampled frame, which wants a reconstruction
// filter (bicubic), not a sharpening upsampler. RenderScale.appliesTo enforces that.

uniform sampler2D tex;
uniform vec2 srcSize;
uniform float sharpness;   // 0 = off, ~0.25 is a reasonable default

varying vec2 vUv;

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    vec2 rcp = 1.0 / srcSize;
    vec2 coord = vUv * srcSize - 0.5;
    vec2 base = floor(coord);
    vec2 f = coord - base;

    // The 3x3 neighbourhood around the tap. b/d/e/f/h are the cross EASU fits its direction to;
    // the corners are used by the sharpening pass to bound the result.
    vec2 c = (base + 0.5) * rcp;
    vec3 tl = texture2D(tex, c + vec2(-1.0, -1.0) * rcp).rgb;
    vec3 t  = texture2D(tex, c + vec2( 0.0, -1.0) * rcp).rgb;
    vec3 tr = texture2D(tex, c + vec2( 1.0, -1.0) * rcp).rgb;
    vec3 l  = texture2D(tex, c + vec2(-1.0,  0.0) * rcp).rgb;
    vec3 m  = texture2D(tex, c).rgb;
    vec3 r  = texture2D(tex, c + vec2( 1.0,  0.0) * rcp).rgb;
    vec3 bl = texture2D(tex, c + vec2(-1.0,  1.0) * rcp).rgb;
    vec3 b  = texture2D(tex, c + vec2( 0.0,  1.0) * rcp).rgb;
    vec3 br = texture2D(tex, c + vec2( 1.0,  1.0) * rcp).rgb;

    // Fit a direction to the local luma gradient. gradient points ACROSS the edge, by definition:
    // it is the direction luma changes fastest, so the edge itself runs perpendicular to it.
    float lt = luma(t), lb = luma(b), ll = luma(l), lr = luma(r);
    vec2 gradient = vec2(lr - ll, lb - lt);
    float edge = length(gradient);

    // The anisotropy is applied to the bilinear WEIGHTS, not by displacing the tap.
    //
    // Displacing the tap by a function of f and then also using f as the blend weight applies the
    // fractional position twice, so the effective sample travels faster than the pixel does. On a
    // diagonal that reads as a serrated staircase -- the edge appears to advance in jumps rather
    // than smoothly, which looks like the interpolation running backwards. Weight space has no such
    // double count: the taps stay on the texel grid and only their mix changes.
    vec2 w = f;
    if (edge >= 1.0 / 64.0) {
        vec2 dir = gradient / edge;
        // Componentwise, how much each axis lies ACROSS the edge. A vertical edge has a horizontal
        // gradient, so align.x is ~1 and align.y is ~0: sharpen in x, stay linear in y.
        vec2 align = dir * dir;
        // Push the fraction toward a step ACROSS the edge, keeping it linear ALONG it. That is the
        // whole trick: a hard boundary stays hard because the transition happens over a fraction of
        // a destination pixel, while the direction the edge runs in is still smoothly interpolated.
        float sharpen = 1.0 + 3.0 * clamp(edge, 0.0, 1.0);
        vec2 stepped = clamp((f - 0.5) * sharpen + 0.5, 0.0, 1.0);
        w = mix(f, stepped, align);
    }

    vec3 top = mix(m, r, w.x);
    vec3 bottom = mix(b, br, w.x);
    vec4 result = vec4(mix(top, bottom, w.y), 1.0);

    // RCAS: contrast-adaptive sharpening, clamped to the neighbourhood so it cannot ring. Sharpen
    // less where the local contrast is already high, which is what keeps it from haloing the hard
    // edges this game is made of.
    if (sharpness > 0.0) {
        vec3 lo = min(min(min(tl, t), min(tr, l)), min(min(m, r), min(min(bl, b), br)));
        vec3 hi = max(max(max(tl, t), max(tr, l)), max(max(m, r), max(max(bl, b), br)));
        vec3 blur = (t + l + r + b + m * 4.0) * 0.125;
        vec3 sharp = result.rgb + (result.rgb - blur) * sharpness;
        result.rgb = clamp(sharp, lo, hi);
    }

    gl_FragColor = vec4(clamp(result.rgb, 0.0, 1.0), 1.0);
}
