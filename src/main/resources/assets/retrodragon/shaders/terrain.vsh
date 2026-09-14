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

// The vertex's luminance at full daylight, and the luminance block light alone gives it. Beta baked
// the time of day into gl_Color and re-meshed the world whenever it moved; this pair plus the
// uniform below is what replaces that. See TerrainLight.
//
// NEGATIVE darkness means the pair is not there -- the array is not enabled, so reading it would
// give the default generic attribute and light the world by (0, 0), which is black. That is the
// state under -Dretroperf.terrainLight=false and on a run whose colours are still baked.
attribute vec2 terrainLight;
uniform float ambientDarkness;

void main() {
    vec4 eye = gl_ModelViewMatrix * gl_Vertex;
    gl_Position = gl_ProjectionMatrix * eye;
    vEye = eye.xyz;
    vUv = gl_MultiTexCoord0.xy;
    // Beta's own brightness curve, applied here instead of baked into the colour by the mesher.
    //
    // The vertex carries the luminance it has at full daylight, which is a value ON that curve, so
    // inverting it recovers the (fractional, because smooth lighting averages corners) light level
    // that produced it. Subtract the darkness the uniform carries, put it back through the curve,
    // and take whichever is brighter -- that, or the light a torch gives it, which no time of day
    // can take away. At darkness 0 the inversion and the curve cancel and this is the colour beta
    // would have baked.
    float lum = 1.0;
    if (ambientDarkness >= 0.0) {
        float above = max(terrainLight.x - 0.05, 0.0);
        float daylightLevel = 4.0 * above / (0.95 + 3.0 * above);
        float level = max(daylightLevel - ambientDarkness * (1.0 / 15.0), 0.0);
        lum = max(level / (4.0 - 3.0 * level) * 0.95 + 0.05, terrainLight.y);
    }
    // Alpha is left alone: light does not make water or glass more transparent.
    vColor = vec4(gl_Color.rgb * lum, gl_Color.a);
    vSpriteTexels = spriteTexels;
    gl_FogFragCoord = length(eye.xyz);
}
