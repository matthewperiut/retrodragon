package com.periut.retrodragon.api;

/**
 * What the game was drawing when a batch was captured.
 *
 * <p>Beta has no render phases: everything is immediate-mode GL issued from wherever in the frame
 * the code happens to be, and the only thing a draw carries is the state it was made under. A
 * shader extension needs more than that -- "the sky" and "a mob" are different programs even when
 * their GL state is identical -- and every previous attempt at this ended up rediscovering the phase
 * from the bound texture, which works until two things share a texture and then silently does not.
 *
 * <p>So the engine tracks it instead, from mixins on the methods that define each phase, and stamps
 * every captured batch. An extension reads it in {@link ShaderExtension#routeProgram} and gets an
 * exact answer for free rather than a heuristic it had to write.
 *
 * <p>The values are small and dense because they are packed into the draw list four bits at a time;
 * {@link #COUNT} is what that width has to cover.
 */
public final class DrawPhase {
	/**
	 * Outside any phase the engine has a name for, and outside world rendering entirely.
	 *
	 * <p>The value in force before the frame starts and between screens, so it is what an unnamed
	 * draw gets by default -- and it counts as NOT world. That default matters: it is the one a
	 * mistake falls back to, and the two mistakes are not symmetric. Treating an unknown GUI draw as
	 * world puts the HUD through a shader pack's linear target and its composite re-gammas it, so
	 * every string of text and every inventory comes out washed out. Treating an unknown world draw
	 * as GUI merely leaves it unshaded.
	 *
	 * <p>Inside world rendering the catch-all is {@link #WORLD}, which IS world.
	 */
	public static final int NONE = 0;

	/** The sky dome, and the void plane below it. */
	public static final int SKY = 1;
	/** The sunrise/sunset fan, drawn between the dome and the celestials. */
	public static final int SKY_SUNSET = 2;
	/** Sun, moon and stars. */
	public static final int CELESTIAL = 3;
	public static final int CLOUDS = 4;

	/** Terrain layer 0. Also covers the block entities beta draws in the same sweep. */
	public static final int TERRAIN_OPAQUE = 5;
	/** Terrain layer 1 -- water and ice. */
	public static final int TERRAIN_TRANSLUCENT = 6;

	public static final int ENTITIES = 7;
	/** The billboarded flames on a burning entity. Beta draws them unlit and fullbright. */
	public static final int ENTITY_FIRE = 8;
	/**
	 * The vanilla blob shadow: a dark circle projected onto the ground.
	 *
	 * <p>Its own phase because an extension with a real shadow pass wants it gone, and dropping it by
	 * texture id means hard-coding {@code %clamp%/misc/shadow.png} in every such extension.
	 */
	public static final int ENTITY_SHADOW = 9;

	/** The block-breaking crack overlay. */
	public static final int BLOCK_DAMAGE = 10;
	/** The selection box outline. */
	public static final int BLOCK_OUTLINE = 11;

	public static final int PARTICLES = 12;
	/** Rain and snow sheets. */
	public static final int WEATHER = 13;

	/** The first-person hand and held item, plus the fire/water/in-wall screen overlays. */
	public static final int HAND = 14;

	/** Everything after the world: HUD, inventory, chat, menus. */
	public static final int GUI = 15;

	/**
	 * Inside world rendering, but not in any more specific phase: a block entity, a mod's own
	 * geometry, anything beta draws between the passes the engine names.
	 *
	 * <p>Distinct from {@link #NONE} precisely so the default outside the world can be safe. This one
	 * is world and is shaded; that one is not.
	 */
	public static final int WORLD = 17;

	/**
	 * Geometry that exists ONLY to cast a shadow: recorded, replayed into the shadow map, and never
	 * drawn on screen.
	 *
	 * <p>The first-person player body is why. Beta does not render it -- {@code renderEntities} skips
	 * the camera entity unless the view is third-person -- so in first person there is no batch to
	 * replay and the player casts no shadow at all, which is glaring the moment anything else does.
	 * The fix is to render the model anyway inside {@link ShaderApi#beginCasterOnly}, where the
	 * batches are captured but excluded from every colour pass.
	 */
	public static final int CASTER_ONLY = 16;

	/** One past the largest phase. */
	public static final int COUNT = 18;

	private static final String[] NAMES = {
		"none", "sky", "sky-sunset", "celestial", "clouds",
		"terrain-opaque", "terrain-translucent", "entities", "entity-fire", "entity-shadow",
		"block-damage", "block-outline", "particles", "weather", "hand", "gui", "caster-only",
		"world",
	};

	private DrawPhase() {
	}

	public static String name(int phase) {
		return phase >= 0 && phase < NAMES.length ? NAMES[phase] : "phase#" + phase;
	}

	/**
	 * Whether this phase is part of the world, as opposed to the GUI drawn over it.
	 *
	 * <p>The distinction the world redirect turns on: world phases render into the extension's own
	 * colour target and go through its composite chain; everything else goes straight to the
	 * swapchain, because tonemapping the inventory screen is not what anyone means by a shader pack.
	 *
	 * <p>{@link #NONE} does NOT count -- see its own note. The catch-all INSIDE world rendering is
	 * {@link #WORLD}, which does.
	 */
	public static boolean isWorld(int phase) {
		return phase != GUI && phase != NONE;
	}

	/**
	 * Whether this phase is opaque geometry that a shadow map should contain.
	 *
	 * <p>Sky, clouds, weather and particles are excluded because a shadow map made from them is
	 * wrong rather than merely expensive: the sky dome surrounds the camera, so it would occlude
	 * everything, and rain would strobe the whole world dark. The blob shadow is excluded because it
	 * is itself a fake shadow.
	 *
	 * <p><b>{@link #HAND} is excluded, and that is not a stylistic call.</b> Beta resets the
	 * modelview before drawing the held item, so the item sits a foot in front of a camera at the
	 * origin with no world rotation applied -- its world position is a FICTION that swings around the
	 * player as they turn. Rendering that into the shadow map casts the held item's shadow onto
	 * whatever part of the world the fiction happens to land on, sliding across the ground as the
	 * player looks about. The first-person hand is also the one piece of geometry guaranteed to be
	 * between the eye and everything else, so its bogus shadow lands where it is most visible.
	 */
	public static boolean castsShadow(int phase) {
		return switch (phase) {
			case TERRAIN_OPAQUE, ENTITIES, CASTER_ONLY, WORLD -> true;
			default -> false;
		};
	}
}
