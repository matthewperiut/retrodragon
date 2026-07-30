// Fixed-function emulation for beta 1.7.3, in WGSL.
//
// This is the output contract of the GLShim: beta and every unported mod draw through
// immediate-mode fixed-function GL, the shim accumulates that state, and every draw ends up here.
// There is no fallback -- under GL compat an unported path still renders because the driver handles
// it, but under WebGPU nothing renders until this shader covers it. So it has to be complete
// rather than incremental.
//
// VERTEX LAYOUT IS BETA'S OWN, UNCHANGED: 32-byte stride, matching what Tessellator writes and what
// RetroDragon's VertexSink already produces. Keeping it identical means the existing terrain mesher
// feeds this path with no rewrite, and the CPU-side format stays one thing across both backends.
//
//   offset 0   position   float32x3
//   offset 12  uv         float32x2
//   offset 20  color      unorm8x4     (vertex colour; beta bakes light into this)
//   offset 24  normal     snorm8x4     (3 bytes + 1 pad; only meaningful when lighting is on)

struct Uniforms {
    // Fixed function keeps these separate, and so do we: beta pushes/pops modelview constantly
    // while projection changes rarely, and the shim only re-uploads what actually changed.
    modelView   : mat4x4<f32>,
    projection  : mat4x4<f32>,

    // glColor. Fixed-function semantics: a vertex colour ARRAY replaces the current colour, so the
    // shim sets this to white whenever the draw supplied per-vertex colours.
    colorModulator : vec4<f32>,

    fogColor    : vec4<f32>,
    // x = mode (0 linear, 1 exp, 2 exp2), y = start / density, z = end, w = 1/(end-start)
    fogParams   : vec4<f32>,

    // x = alpha test reference, y = alpha test enabled, z = texture enabled, w = lighting enabled
    flags       : vec4<f32>,

    // beta's Lighting.turnOn: two directional lights, diffuse 0.6, light-model ambient 0.4.
    // Directions are EYE space -- that is how glLightfv stored them once the modelview was applied.
    lightDir0   : vec4<f32>,
    lightDir1   : vec4<f32>,
    lightAmbient: vec4<f32>,

    // Which vertex attributes this batch actually WROTE. x = colour, y = normal, z = texture.
    //
    // Beta's Tessellator only fills the colour and normal slots when the caller supplied them
    // (`hasColor`, `hasNormals`) and leaves whatever the previous batch left behind otherwise --
    // under GL that is harmless, because the matching client-state array simply stays disabled and
    // the fixed-function pipeline falls back to the current colour and normal. A vertex buffer has
    // no such per-attribute switch: the shader would read stale bytes as if they were data, which
    // shows as the previous draw's colours bleeding into an untinted one.
    vertexFlags : vec4<f32>,
};

@group(0) @binding(0) var<uniform> u : Uniforms;
@group(0) @binding(1) var texSampler : sampler;
@group(0) @binding(2) var texture    : texture_2d<f32>;

struct VertexIn {
    @location(0) position : vec3<f32>,
    @location(1) uv       : vec2<f32>,
    @location(2) color    : vec4<f32>,
    @location(3) normal   : vec4<f32>,
};

struct VertexOut {
    @builtin(position) clipPosition : vec4<f32>,
    @location(0) uv       : vec2<f32>,
    @location(1) color    : vec4<f32>,
    // Eye-space distance, for fog. Fixed-function GL derives fog from eye distance, not from the
    // depth buffer value, so it must be carried explicitly.
    @location(2) fogDepth : f32,
};

@vertex
fn vs_main(in : VertexIn) -> VertexOut {
    var out : VertexOut;
    let eye = u.modelView * vec4<f32>(in.position, 1.0);
    var clip = u.projection * eye;

    // GL clips to -w <= z <= w; WebGPU (like D3D and Metal) clips to 0 <= z <= w. The projection
    // matrix arrives straight from beta's own glOrtho/gluPerspective, so the depth range is
    // remapped HERE rather than by rewriting every matrix the game builds. Without this the near
    // half of the depth range is clipped away entirely -- geometry in front of the camera midpoint
    // simply vanishes, which reads as "nothing renders" rather than as a depth bug.
    clip.z = (clip.z + clip.w) * 0.5;

    out.clipPosition = clip;
    // A batch that wrote no texture coordinates gets the current one, which beta leaves at the
    // origin -- NOT whatever bytes the previous batch left in that slot. Entity shadows write no UVs,
    // and reading stale ones makes them sample the block atlas.
    out.uv = select(vec2<f32>(0.0, 0.0), in.uv, u.vertexFlags.z > 0.5);
    out.fogDepth = length(eye.xyz);

    var color = u.colorModulator;
    if (u.vertexFlags.x > 0.5) {
        color = in.color * color;
    }

    if (u.flags.w > 0.5) {
        // Two-directional-light diffuse, exactly beta's Lighting.turnOn. Normals are transformed by
        // the modelview's rotation only; beta never uses a non-uniform scale on lit geometry.
        // Without a per-vertex normal, GL uses the current one, which beta leaves at +Y.
        let rawNormal = select(vec3<f32>(0.0, 1.0, 0.0), in.normal.xyz, u.vertexFlags.y > 0.5);
        let n = normalize((u.modelView * vec4<f32>(rawNormal, 0.0)).xyz);
        let d0 = max(dot(n, u.lightDir0.xyz), 0.0);
        let d1 = max(dot(n, u.lightDir1.xyz), 0.0);
        let lit = min(u.lightAmbient.rgb + (d0 + d1) * 0.6, vec3<f32>(1.0, 1.0, 1.0));
        color = vec4<f32>(color.rgb * lit, color.a);
    }

    out.color = color;
    return out;
}

@fragment
fn fs_main(in : VertexOut) -> @location(0) vec4<f32> {
    var color = in.color;
    if (u.flags.z > 0.5) {
        color = color * textureSample(texture, texSampler, in.uv);
    }

    // Alpha test. GL discards the whole fragment, and the compat profile applies it to shader
    // output too -- beta leaves it enabled at ref 0.1 almost everywhere, and cutout foliage depends
    // on it. discard is the only faithful translation; blending cannot reproduce it.
    if (u.flags.y > 0.5 && color.a <= u.flags.x) {
        discard;
    }

    // Fog, all three GL modes. Beta uses EXP underwater (density 0.1) and in lava (density 2.0),
    // LINEAR everywhere else, so treating fog as linear-only is visibly wrong in two places.
    var fog : f32;
    if (u.fogParams.x < 0.5) {
        fog = (u.fogParams.z - in.fogDepth) * u.fogParams.w;
    } else if (u.fogParams.x < 1.5) {
        fog = exp(-u.fogParams.y * in.fogDepth);
    } else {
        let fd = u.fogParams.y * in.fogDepth;
        fog = exp(-fd * fd);
    }
    fog = clamp(fog, 0.0, 1.0);

    return vec4<f32>(mix(u.fogColor.rgb, color.rgb, fog), color.a);
}
