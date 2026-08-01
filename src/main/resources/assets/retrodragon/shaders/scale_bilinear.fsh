#version 120

// Bilinear resample, done explicitly rather than by leaning on GL_LINEAR.
//
// Explicit because the attachment's filter state is shared with the other resolve paths, and a
// filter that silently depends on someone else having called glTexParameteri is the kind that breaks
// when a second caller appears. Four taps and a pair of mixes cost nothing at this resolution.

uniform sampler2D tex;
uniform vec2 srcSize;

varying vec2 vUv;

void main() {
    vec2 coord = vUv * srcSize - 0.5;
    vec2 base = floor(coord);
    vec2 f = coord - base;

    vec2 rcp = 1.0 / srcSize;
    vec4 c00 = texture2D(tex, (base + vec2(0.5, 0.5)) * rcp);
    vec4 c10 = texture2D(tex, (base + vec2(1.5, 0.5)) * rcp);
    vec4 c01 = texture2D(tex, (base + vec2(0.5, 1.5)) * rcp);
    vec4 c11 = texture2D(tex, (base + vec2(1.5, 1.5)) * rcp);

    gl_FragColor = mix(mix(c00, c10, f.x), mix(c01, c11, f.x), f.y);
}
