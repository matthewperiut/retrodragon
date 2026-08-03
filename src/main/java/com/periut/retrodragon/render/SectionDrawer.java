package com.periut.retrodragon.render;

import com.periut.retrodragon.api.RetroSettings;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.render.chunk.ChunkBuilder;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;

/**
 * Draws visible section VBOs, replacing vanilla's {@code glCallLists} path.
 *
 * Sections are grouped by their 1024-block cell exactly as vanilla's ChunkRenderer did, so each
 * group needs one modelview; within a group the sections draw back to back with no matrix work.
 * Client-array state is set up once per call rather than per section -- vanilla's Tessellator
 * enabled and disabled the arrays around every single draw.
 *
 * ponytail: one glDrawArrays per section. If draw-call overhead ever shows up in the bench, the
 * upgrade is a shared per-cell arena buffer plus glMultiDrawArrays -- one call per group. Not
 * done now because it is an allocator's worth of complexity for an unmeasured win.
 */
public final class SectionDrawer {
	/**
	 * The alpha the CUTOUT pass tests against, in place of beta's own 0.1, and only for that pass.
	 *
	 * <p>Beta compares an unfiltered texel against 0.1, which is fine when alpha is only ever 0 or 1.
	 * Every form of filtering this renderer added -- the mip chain, RGSS's four taps, alpha-to-coverage
	 * -- produces alpha BETWEEN the two, and at 0.1 anything a filter so much as touched still counts
	 * as solid: a quarter-covered texel passes, so a cutout grows a texel at every mip level. On
	 * vanilla terrain.png that took glass from 27.7% covered to fully solid three levels down, which
	 * is a window that stops being a window as you walk away from it.
	 *
	 * <p>Half is the only threshold with no bias in either direction -- a filtered texel survives when
	 * more than half of what it averaged was solid -- and it is what {@link Mipmapper} restores each
	 * tile's coverage against. The two are the same number and have to move together.
	 *
	 * <p>Level 0 is unaffected on any vanilla or HD sheet: the only fractional alphas in beta's atlas
	 * are water, ice and the break overlay, none of which is drawn in this pass.
	 */
	private static final float CUTOUT_ALPHA_REF = Mipmapper.CUTOFF / 255.0F;

	/**
	 * What beta leaves the alpha ref at for everything else, restored after the cutout pass.
	 *
	 * <p>{@code EntityRenderer} sets it exactly twice -- 0.01 around the weather pass, then back to
	 * this -- so this is the standing value at the point terrain is drawn, and the value the
	 * translucent pass below wants: water and ice carry a real fractional alpha (0.53 and 0.62) that
	 * a half-way test would be far too close to.
	 */
	private static final float BETA_ALPHA_REF = 0.1F;

	private static final List<ChunkBuilder> visible = new ArrayList<>();
	private static final boolean DIAG = Boolean.getBoolean("retroperf.diag");
	/** Artifact hunting: bit 0 = opaque layer, bit 1 = translucent layer. Default draws both. */
	private static final int LAYER_MASK = Integer.getInteger("retroperf.layerMask", 3);
	private static int diagFrames;
	private static int lastLayer;
	private static double lastCamX, lastCamY, lastCamZ;

	private SectionDrawer() {
	}

	/**
	 * @return how many sections were drawn, matching the count vanilla's renderChunks returned.
	 */
	public static int draw(ChunkBuilder[] sorted, int from, int to, int layer,
			boolean occlusionEnabled, double camX, double camY, double camZ) {
		if ((LAYER_MASK & 1 << layer) == 0) {
			return 0;
		}
		// One BFS per frame, on the first layer; layer 1 reuses the cached result.
		if (layer == 0) {
			OcclusionCuller.update(sorted, camX, camY, camZ);
		}
		visible.clear();
		int eligible = 0;
		int occluded = 0;
		long verts = 0;
		for (int i = from; i < to; i++) {
			ChunkBuilder section = sorted[i];
			if (section.renderLayerEmpty[layer] || !section.inFrustum) {
				continue;
			}
			if (occlusionEnabled && !section.unoccluded) {
				continue;
			}
			if (!OcclusionCuller.isVisible(section)) {
				occluded++;
				continue;
			}
			eligible++;
			int count = ((RetroSection) section).retroperf$mesh(layer).vertexCount();
			if (count > 0) {
				verts += count;
				visible.add(section);
			}
		}
		if (DIAG && ++diagFrames % 600 == 0) {
			System.out.println("RETROPERF-DIAG layer=" + layer + " eligible=" + eligible
				+ " withMesh=" + visible.size() + " occluded=" + occluded + " verts=" + verts);
		}
		lastLayer = layer;
		lastCamX = camX;
		lastCamY = camY;
		lastCamZ = camZ;
		if (visible.isEmpty()) {
			return 0;
		}
		sortForMerging(layer);
		return drawList(layer, camX, camY, camZ);
	}

	/**
	 * Redraws the list from the most recent {@link #draw}, for vanilla's {@code renderLastChunks}.
	 *
	 * Under fancy graphics the translucent layer is rendered twice: once with the colour mask off
	 * to lay down depth, then again through renderLastChunks with colour enabled. That second call
	 * is water's only visible pass, so skipping it renders the ocean as bare seabed.
	 */
	public static void replayLast() {
		if (!visible.isEmpty()) {
			drawList(lastLayer, lastCamX, lastCamY, lastCamZ);
		}
	}

	/**
	 * Orders the opaque pass so sections adjacent in the shared arena are drawn back to back, which
	 * is what lets them collapse into a single draw.
	 *
	 * <p>Cell first, arena position second. The draw loop starts a new modelview whenever the
	 * 1024-block cell changes and a batch cannot merge across a uniform change, so sorting by arena
	 * position alone would interleave cells, push a matrix per section, and merge nothing.
	 *
	 * <p>OPAQUE only. That pass is depth-tested, so submission order cannot change the result. The
	 * translucent pass is blended and must keep the back-to-front order vanilla sorted it into; it
	 * merges only where the arena happens to agree.
	 *
	 * <p>No-op under GL, and no-op when the arena is off -- a section owning its own buffer has no
	 * position to sort by.
	 */
	private static void sortForMerging(int layer) {
		if (layer != 0 || !WebGpuFrame.active() || !TerrainArena.enabled() || visible.size() < 2) {
			return;
		}
		visible.sort((a, b) -> {
			int byCell = Long.compare(cell(a), cell(b));
			return byCell != 0 ? byCell : Integer.compare(
				((RetroSection) a).retroperf$mesh(0).arenaVertex(),
				((RetroSection) b).retroperf$mesh(0).arenaVertex());
		});
	}

	/** The section's 1024-block cell, packed so it can be compared in one go. */
	private static long cell(ChunkBuilder section) {
		return (long) (section.cameraOffsetX >> 10 & 0x1FFFFF) << 42
			| (long) (section.cameraOffsetY >> 10 & 0x1FFFFF) << 21
			| section.cameraOffsetZ >> 10 & 0x1FFFFF;
	}

	private static int drawList(int layer, double camX, double camY, double camZ) {
		// Layer 0 is beta's opaque+cutout pass -- the one where leaves, grass and glass are drawn
		// through the alpha test. Layer 1 is genuinely translucent and must keep blending.
		if (layer == 0) {
			AntiAliasing.beginCutout();
			// Both backends: under WebGPU this reaches the shim's tracked state and comes out as the
			// terrain pipeline's discard threshold, under GL it is the fixed-function alpha test.
			GL11.glAlphaFunc(GL11.GL_GREATER, CUTOUT_ALPHA_REF);
		}
		// Our own program, when enabled: it is the only way to get a stable mip level on beta's
		// atlas. Falls back to fixed function on any compile/link failure.
		//
		// The atlas parameters are re-read here rather than baked in: a content API can grow the
		// sheet (RetroAPI) or replace it with a stitched one (StationAPI), and a texture-pack switch
		// can change it mid-run. Two reflective field reads per frame, no allocation.
		BlockAtlas.refresh();
		boolean shader = TerrainShader.begin(BlockAtlas.texels(), BlockAtlas.texels(),
			BlockAtlas.tileTexels(), RetroSettings.isMipmap() ? BlockAtlas.mipLevels() : 0.0F);
		GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
		GL11.glEnableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
		GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
		GL11.glEnableClientState(GL11.GL_NORMAL_ARRAY);

		int drawn = 0;
		int groupX = Integer.MIN_VALUE;
		int groupY = Integer.MIN_VALUE;
		int groupZ = Integer.MIN_VALUE;
		boolean matrixPushed = false;

		for (int i = 0; i < visible.size(); i++) {
			ChunkBuilder section = visible.get(i);
			if (section.cameraOffsetX != groupX || section.cameraOffsetY != groupY || section.cameraOffsetZ != groupZ) {
				if (matrixPushed) {
					GL11.glPopMatrix();
				}
				groupX = section.cameraOffsetX;
				groupY = section.cameraOffsetY;
				groupZ = section.cameraOffsetZ;
				GL11.glPushMatrix();
				GL11.glTranslatef((float) (groupX - camX), (float) (groupY - camY), (float) (groupZ - camZ));
				matrixPushed = true;
			}
			((RetroSection) section).retroperf$mesh(layer).draw();
			drawn++;
		}
		if (matrixPushed) {
			GL11.glPopMatrix();
		}

		GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
		GL11.glDisableClientState(GL11.GL_TEXTURE_COORD_ARRAY);
		GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
		GL11.glDisableClientState(GL11.GL_NORMAL_ARRAY);
		// Critical: beta's immediate-mode Tessellator passes client-memory pointers, which GL
		// reinterprets as buffer offsets while any array buffer is bound. Leaving this bound
		// makes every later legacy draw read garbage.
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		if (shader) {
			TerrainShader.end();
		}
		if (layer == 0) {
			AntiAliasing.endCutout();
			// Everything after this -- the translucent pass, entities, the block-breaking overlay, the
			// held item -- is beta's own drawing and expects beta's own threshold back.
			GL11.glAlphaFunc(GL11.GL_GREATER, BETA_ALPHA_REF);
		}
		return drawn;
	}
}
