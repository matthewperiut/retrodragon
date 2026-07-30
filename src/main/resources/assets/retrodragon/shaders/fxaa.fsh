#version 120

// FXAA 3.11 (the widely used PC-lite formulation).
//
// Chosen over MSAA because MSAA is actively wrong on beta's terrain: it anti-aliases every quad
// boundary, and beta's world is thousands of adjacent coplanar 1x1 quads, so partial sample
// coverage opens a one-pixel seam on the edge of every block (measured: 11215 seam pixels at 4x,
// 0 without). FXAA runs on the already-resolved image, so it cannot open seams between quads --
// it only softens luminance edges that are already there.
//
// The threshold matters more here than in a typical game: beta is pixel art, and an over-eager
// FXAA turns crisp texture detail to mush. Defaults are deliberately conservative so it catches
// block silhouettes while leaving texture interiors alone.

uniform sampler2D tex;
uniform vec2 rcpFrame;          // (1/width, 1/height)
uniform float edgeThreshold;    // relative luma contrast required to blend
uniform float edgeThresholdMin; // absolute floor, keeps dark areas from shimmering

varying vec2 vUv;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 rgbM = texture2D(tex, vUv).rgb;
    float lM = luma(rgbM);
    float lN = luma(texture2D(tex, vUv + vec2(0.0, -1.0) * rcpFrame).rgb);
    float lS = luma(texture2D(tex, vUv + vec2(0.0, 1.0) * rcpFrame).rgb);
    float lW = luma(texture2D(tex, vUv + vec2(-1.0, 0.0) * rcpFrame).rgb);
    float lE = luma(texture2D(tex, vUv + vec2(1.0, 0.0) * rcpFrame).rgb);

    float lMin = min(lM, min(min(lN, lS), min(lW, lE)));
    float lMax = max(lM, max(max(lN, lS), max(lW, lE)));
    float range = lMax - lMin;

    // Flat enough to leave alone. This early-out is most of the reason FXAA is cheap.
    if (range < max(edgeThresholdMin, lMax * edgeThreshold)) {
        gl_FragColor = vec4(rgbM, 1.0);
        return;
    }

    float lNW = luma(texture2D(tex, vUv + vec2(-1.0, -1.0) * rcpFrame).rgb);
    float lNE = luma(texture2D(tex, vUv + vec2(1.0, -1.0) * rcpFrame).rgb);
    float lSW = luma(texture2D(tex, vUv + vec2(-1.0, 1.0) * rcpFrame).rgb);
    float lSE = luma(texture2D(tex, vUv + vec2(1.0, 1.0) * rcpFrame).rgb);

    // Edge direction from the corner luma gradient, then walk along it.
    vec2 dir;
    dir.x = -((lNW + lNE) - (lSW + lSE));
    dir.y = ((lNW + lSW) - (lNE + lSE));

    float dirReduce = max((lNW + lNE + lSW + lSE) * 0.25 * (1.0 / 8.0), 1.0 / 128.0);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    dir = clamp(dir * rcpDirMin, vec2(-8.0), vec2(8.0)) * rcpFrame;

    vec3 rgbA = 0.5 * (
        texture2D(tex, vUv + dir * (1.0 / 3.0 - 0.5)).rgb +
        texture2D(tex, vUv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
        texture2D(tex, vUv + dir * -0.5).rgb +
        texture2D(tex, vUv + dir * 0.5).rgb);

    // If the wider tap left the local luma range, it crossed onto unrelated geometry; fall back.
    float lB = luma(rgbB);
    gl_FragColor = vec4((lB < lMin || lB > lMax) ? rgbA : rgbB, 1.0);
}
