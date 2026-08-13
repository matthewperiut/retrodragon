// glCopyTexSubImage2D: the framebuffer region, staged into a texture of its own, drawn into the
// destination texture. See FramebufferCopy for why this is a draw rather than a texture copy --
// the two textures are neither the same format (the swapchain is BGRA on Metal, every texture the
// store hands out is RGBA) nor the same way up.
//
// No uniforms at all. The destination rectangle is the pass viewport and the source rectangle was
// already staged, so both are exactly the full quad: what is left is the vertical flip.

@group(0) @binding(0) var srcSampler : sampler;
@group(0) @binding(1) var srcTexture : texture_2d<f32>;

struct VertexOut {
    @builtin(position) position : vec4<f32>,
    @location(0) uv : vec2<f32>,
};

// One oversized triangle rather than two, as in scale.wgsl: three vertices, no shared edge.
@vertex
fn vs_main(@builtin(vertex_index) index : u32) -> VertexOut {
    var out : VertexOut;
    let p = vec2<f32>(f32((index << 1u) & 2u), f32(index & 2u));
    out.position = vec4<f32>(p * vec2<f32>(2.0, -2.0) + vec2<f32>(-1.0, 1.0), 0.0, 1.0);
    // THE FLIP, and the whole reason a copy would not do. GL's window origin is bottom-left, so the
    // first row of the destination image is the BOTTOM row of the region read from the framebuffer;
    // WebGPU's row 0 is the top of both. p.y already runs 0..1 down the destination.
    out.uv = vec2<f32>(p.x, 1.0 - p.y);
    return out;
}

// textureSampleLevel with an explicit 0: the staging texture has one level and its sampler clamps
// its LOD, so nothing else was ever going to be read, and no derivative is needed.
@fragment
fn fs_main(in : VertexOut) -> @location(0) vec4<f32> {
    return textureSampleLevel(srcTexture, srcSampler, in.uv, 0.0);
}
