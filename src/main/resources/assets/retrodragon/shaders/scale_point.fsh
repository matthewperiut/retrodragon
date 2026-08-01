#version 120

// Point resample. Used for both "nearest" and "integer" -- the difference between them is the
// destination rectangle the caller draws into, not the sampling, so one shader covers both.
//
// The tap is snapped to the source texel CENTRE rather than left to the sampler, so the result does
// not depend on the texture's filter state having been set correctly. That matters here because the
// same colour attachment is sampled with LINEAR by the FXAA path.

uniform sampler2D tex;
uniform vec2 srcSize;

varying vec2 vUv;

void main() {
    vec2 texel = floor(vUv * srcSize) + 0.5;
    gl_FragColor = texture2D(tex, texel / srcSize);
}
