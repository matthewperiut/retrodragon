#version 120

// Beta's terrain, drawn through our own program instead of the fixed-function pipeline.
// Deliberately uses compatibility built-ins (gl_ModelViewProjectionMatrix, gl_Color,
// gl_MultiTexCoord0, gl_Fog) so the surrounding fixed-function state beta sets up still applies
// and the result matches vanilla without re-plumbing matrices, fog or colour.

varying vec2 vUv;
varying vec4 vColor;
// Eye-space position. Unlike UV, this is CONTINUOUS across block faces, which is what makes a
// stable mip level possible -- see terrain.fsh.
varying vec3 vEye;

// The owning sprite's edge length in texels, 0 when the atlas is a plain grid and the uniform pitch
// describes it. Only a STITCHED sheet needs it: sprites sit at mixed sizes there, and no single
// pitch is right for all of them. Written by every vertex of a quad with the same value, so the
// interpolator reproduces it exactly and GLSL 120's lack of `flat` costs nothing.
attribute float spriteTexels;
varying float vSpriteTexels;

void main() {
    vec4 eye = gl_ModelViewMatrix * gl_Vertex;
    gl_Position = gl_ProjectionMatrix * eye;
    vEye = eye.xyz;
    vUv = gl_MultiTexCoord0.xy;
    vColor = gl_Color;
    vSpriteTexels = spriteTexels;
    gl_FogFragCoord = length(eye.xyz);
}
