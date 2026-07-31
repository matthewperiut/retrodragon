package com.periut.retrodragon.api;

/**
 * A shader program a {@link ShaderExtension} adds to the engine.
 *
 * <p>Registration hands over WGSL source and a description of what it consumes; the engine compiles
 * it lazily -- the first time a draw actually selects it -- and builds pipelines for it through the
 * same cache the built-in programs use. So an extension's programs cost nothing until something is
 * routed to them, and a program that is never reached in a given world never compiles at all.
 *
 * @param name    a stable identifier, used in pipeline labels and in {@link ShaderApi#programId}
 * @param source  the complete WGSL, with {@code vs_main} and {@code fs_main} entry points
 * @param layout  which vertex stream the program reads
 * @param shaderGroup true if the program declares {@code @group(1)} -- see {@link ShaderResources}
 * @param auxTargets how many colour attachments past the first the fragment stage writes; must not
 *                   exceed what the extension asked for in {@link ShaderApi#requestWorldTarget}
 */
public record ProgramSpec(String name, String source, VertexLayout layout, boolean shaderGroup,
		int auxTargets) {

	/** Which vertex stream a program reads. */
	public enum VertexLayout {
		/**
		 * Beta's own 32-byte Tessellator vertex: position {@code float32x3} at 0, uv
		 * {@code float32x2} at 12, colour {@code unorm8x4} at 20, normal {@code snorm8x4} at 24.
		 *
		 * <p>Everything the game draws immediate-mode arrives in this layout, so this is what sky,
		 * clouds, entities, particles, the hand and the GUI all read.
		 */
		FIXED_FUNCTION,
		/**
		 * The terrain stream, which the engine packs itself and may narrow.
		 *
		 * <p>Position, uv and colour only -- no normal, because beta bakes its face shading into the
		 * vertex colour and the slot was pure bandwidth. The uv and colour widths depend on whether
		 * the compact packing is on, which the engine decides; a program declaring
		 * {@code @location(1) uv : vec2<f32>} reads both correctly, since a vertex format converts on
		 * fetch.
		 */
		TERRAIN,
	}

	/** A program on beta's vertex layout that reads only the engine's per-draw group. */
	public static ProgramSpec of(String name, String source) {
		return new ProgramSpec(name, source, VertexLayout.FIXED_FUNCTION, false, 0);
	}

	/** As {@link #of}, plus the extension's own uniforms and textures in {@code @group(1)}. */
	public static ProgramSpec shaded(String name, String source) {
		return new ProgramSpec(name, source, VertexLayout.FIXED_FUNCTION, true, 0);
	}

	public ProgramSpec withLayout(VertexLayout layout) {
		return new ProgramSpec(name, source, layout, shaderGroup, auxTargets);
	}

	public ProgramSpec withAuxTargets(int auxTargets) {
		return new ProgramSpec(name, source, layout, shaderGroup, auxTargets);
	}
}
