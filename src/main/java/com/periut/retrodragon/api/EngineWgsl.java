package com.periut.retrodragon.api;

/**
 * The WGSL an extension program has to declare to talk to the engine: {@code @group(0)}, the vertex
 * layouts, and the handful of fixed-function conversions every program repeats.
 *
 * <p>Supplied as text rather than documented because WGSL has no preprocessor and no include
 * mechanism. The alternative is every extension transcribing the engine's uniform block by hand,
 * where a field in the wrong place is not a compile error -- it is a shader that reads the fog
 * colour as a matrix row and draws something almost right.
 *
 * <p>Concatenate the pieces a program needs ahead of its own source. Order within a WGSL file does
 * not matter, so the fragments can go in any sequence.
 */
public final class EngineWgsl {
	private EngineWgsl() {
	}

	/**
	 * The engine's per-draw group: the fixed-function state the shim accumulated for this batch.
	 *
	 * <p>Byte-identical to what {@code fixedfunc.wgsl} declares, because it is the same buffer. The
	 * three parameters carried in the {@code w} lanes of the light vectors are terrain-program
	 * business and are named here so the struct matches rather than because a general program wants
	 * them.
	 */
	public static final String GROUP0 = """
		// --- retrodragon @group(0): the engine's per-draw fixed-function state ---------------------
		struct EngineUniforms {
		    modelView      : mat4x4<f32>,
		    projection     : mat4x4<f32>,
		    // glColor, or white when the batch supplied a per-vertex colour array.
		    colorModulator : vec4<f32>,
		    fogColor       : vec4<f32>,
		    // x mode (0 linear, 1 exp, 2 exp2), y start-or-density, z end, w 1/(end-start)
		    fogParams      : vec4<f32>,
		    // x alpha-test reference, y alpha test on, z texturing on, w GL_LIGHTING on
		    flags          : vec4<f32>,
		    // xyz eye-space light direction, w atlas width in texels (terrain only)
		    lightDir0      : vec4<f32>,
		    // xyz eye-space light direction, w maximum mip level (terrain only)
		    lightDir1      : vec4<f32>,
		    // rgb light-model ambient, w rotated-grid supersampling flag (terrain only)
		    lightAmbient   : vec4<f32>,
		    // x colour written, y normal written, z uv written, w atlas tile pitch (terrain only)
		    vertexFlags    : vec4<f32>,
		};

		@group(0) @binding(0) var<uniform> engine : EngineUniforms;
		@group(0) @binding(1) var engineSampler : sampler;
		@group(0) @binding(2) var engineTexture : texture_2d<f32>;
		""";

	/** Beta's Tessellator vertex, as every immediate-mode draw supplies it. */
	public static final String VERTEX_IN = """
		struct VertexIn {
		    @location(0) position : vec3<f32>,
		    @location(1) uv       : vec2<f32>,
		    @location(2) color    : vec4<f32>,
		    @location(3) normal   : vec4<f32>,
		};
		""";

	/**
	 * The terrain stream: no normal, because beta bakes face shading into the vertex colour and the
	 * slot was pure bandwidth on the largest stream the game produces.
	 */
	public static final String TERRAIN_VERTEX_IN = """
		struct VertexIn {
		    @location(0) position : vec3<f32>,
		    @location(1) uv       : vec2<f32>,
		    @location(2) color    : vec4<f32>,
		};
		""";

	/**
	 * The conversions every program repeats, and gets subtly wrong when it writes them itself.
	 *
	 * <ul>
	 * <li>{@code engineClip} remaps GL's {@code -w..w} depth range to WebGPU's {@code 0..w}. Beta's
	 *     projection matrices come straight from its own {@code glFrustum}, so without this the near
	 *     half of the depth range is clipped away and geometry in front of the camera midpoint simply
	 *     vanishes.</li>
	 * <li>{@code engineVertexColor} applies fixed-function colour semantics: a per-vertex colour
	 *     ARRAY replaces the current colour, and a batch that wrote none must not read the previous
	 *     batch's bytes.</li>
	 * <li>{@code engineUv} does the same for texture coordinates -- entity shadows write none, and
	 *     reading stale ones makes them sample the block atlas.</li>
	 * <li>{@code engineFog} is GL's fog in all three modes, by eye distance rather than by depth
	 *     value. A pack that repaints fog still wants this to decide HOW MUCH fog, and to match the
	 *     engine's own draws at the edges.</li>
	 * <li>{@code engineLight} is beta's two-directional-light diffuse, for a program that wants to
	 *     keep the vanilla shading term and only recolour it.</li>
	 * </ul>
	 */
	public static final String HELPERS = """
		fn engineClip(eye : vec4<f32>) -> vec4<f32> {
		    var clip = engine.projection * eye;
		    clip.z = (clip.z + clip.w) * 0.5;
		    return clip;
		}

		fn engineVertexColor(vertexColor : vec4<f32>) -> vec4<f32> {
		    if (engine.vertexFlags.x > 0.5) {
		        return vertexColor * engine.colorModulator;
		    }
		    return engine.colorModulator;
		}

		fn engineUv(uv : vec2<f32>) -> vec2<f32> {
		    return select(vec2<f32>(0.0, 0.0), uv, engine.vertexFlags.z > 0.5);
		}

		/// 1 = no fog, 0 = fully fogged.
		fn engineFog(eyeDistance : f32) -> f32 {
		    var fog : f32;
		    if (engine.fogParams.x < 0.5) {
		        fog = (engine.fogParams.z - eyeDistance) * engine.fogParams.w;
		    } else if (engine.fogParams.x < 1.5) {
		        fog = exp(-engine.fogParams.y * eyeDistance);
		    } else {
		        let fd = engine.fogParams.y * eyeDistance;
		        fog = exp(-fd * fd);
		    }
		    return clamp(fog, 0.0, 1.0);
		}

		fn engineLight(normal : vec4<f32>) -> vec3<f32> {
		    if (engine.flags.w <= 0.5) {
		        return vec3<f32>(1.0);
		    }
		    let raw = select(vec3<f32>(0.0, 1.0, 0.0), normal.xyz, engine.vertexFlags.y > 0.5);
		    let n = normalize((engine.modelView * vec4<f32>(raw, 0.0)).xyz);
		    let d0 = max(dot(n, engine.lightDir0.xyz), 0.0);
		    let d1 = max(dot(n, engine.lightDir1.xyz), 0.0);
		    return min(engine.lightAmbient.rgb + (d0 + d1) * 0.6, vec3<f32>(1.0));
		}

		/// True when the fragment should be discarded by the batch's alpha test.
		fn engineAlphaFails(alpha : f32) -> bool {
		    return engine.flags.y > 0.5 && alpha <= engine.flags.x;
		}

		fn engineTextured() -> bool {
		    return engine.flags.z > 0.5;
		}
		""";
}
