#version 120

// Fullscreen pass. Driven by an immediate-mode quad so no vertex buffer or VAO is needed, which
// keeps the GL 2.1 floor intact.

varying vec2 vUv;

void main() {
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
    vUv = gl_MultiTexCoord0.xy;
}
