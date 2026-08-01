#version 120

// Catmull-Rom bicubic resample.
//
// The filter that earns its cost ABOVE 1.0, where a supersampled frame is being reduced: bilinear
// averages two samples per axis and throws away most of what the extra resolution bought, while
// Catmull-Rom's negative lobes preserve the edge contrast that supersampling was for.
//
// Catmull-Rom rather than a B-spline because B-spline is blurry by construction (it does not
// interpolate its control points) and blur is the one thing this renderer must not add.
//
// The weights can go negative, which can push a channel below zero on a high-contrast edge. Clamped
// at the end: a negative colour is not merely dark, it inverts once anything multiplies by it.

uniform sampler2D tex;
uniform vec2 srcSize;

varying vec2 vUv;

// Catmull-Rom basis for a sample x texels from the tap, with a = -0.5.
float w0(float x) { return ((-0.5 * x + 1.0) * x - 0.5) * x; }
float w1(float x) { return (1.5 * x - 2.5) * x * x + 1.0; }
float w2(float x) { return ((-1.5 * x + 2.0) * x + 0.5) * x; }
float w3(float x) { return (0.5 * x - 0.5) * x * x; }

void main() {
    vec2 coord = vUv * srcSize - 0.5;
    vec2 base = floor(coord);
    vec2 f = coord - base;
    vec2 rcp = 1.0 / srcSize;

    float wx[4];
    float wy[4];
    wx[0] = w0(f.x); wx[1] = w1(f.x); wx[2] = w2(f.x); wx[3] = w3(f.x);
    wy[0] = w0(f.y); wy[1] = w1(f.y); wy[2] = w2(f.y); wy[3] = w3(f.y);

    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            vec2 tap = (base + vec2(float(i) - 0.5, float(j) - 0.5)) * rcp;
            sum += texture2D(tex, tap) * (wx[i] * wy[j]);
        }
    }

    gl_FragColor = vec4(max(sum.rgb, vec3(0.0)), 1.0);
}
