package com.periut.retrodragon.mixin;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.SingleplayerInteractionManager;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame-rate bench. Deliberately IDENTICAL in vantage to the bare-retroapi-template
 * {@code VanillaBenchMixin} (same seed 20260610, same teleport, eye at groundY+24, yaw 0 /
 * pitch 32) so a RetroDragon run and a stock-beta run are the same view and directly comparable --
 * that A/B is the whole premise of this mod.
 *
 * OFF unless asked for. Everything below hijacks the session -- it teleports the player, pins the
 * camera and world time and forces the frame limit off -- so a normal play session must not touch
 * any of it. {@code -Dretroperf.bench=true} arms it; setting {@code bench.autoworld} implies it,
 * since that flag has no other use.
 *
 *   -Dretroperf.bench=true          arm the bench (implied by bench.autoworld)
 *   -Dretroperf.bench.autoworld=N   create the world after N frames (required to bench unattended)
 *   -Dretroperf.bench.x / .z        bench position (default 0,0)
 *   -Dretroperf.bench.warmup=N      frames skipped before recording (default 600)
 *   -Dretroperf.bench.frames=N      frames recorded (default 600)
 *   -Dretroperf.bench.pause=true    open the pause menu after teleport (pure-render, tick frozen)
 *   -Dretroperf.bench.shot=N        screenshot N frames after the vantage settles, then exit.
 *                                   Use this for appearance A/B against -Dretroperf.terrain=false;
 *                                   the vantage is pinned, so the two runs are pixel-comparable.
 */
@Mixin(GameRenderer.class)
public class BenchMixin {
	@Shadow
	private Minecraft client;

	@Unique private int rp$autoWorld = Integer.getInteger("retroperf.bench.autoworld", -1);
	// Not derived from rp$autoWorld: merged @Unique initialisers run in declaration order inside the
	// target's constructor, and depending on that ordering has bitten this mod before.
	@Unique private final boolean rp$enabled = Boolean.getBoolean("retroperf.bench")
		|| Integer.getInteger("retroperf.bench.autoworld", -1) > 0;
	@Unique private final int rp$x = Integer.getInteger("retroperf.bench.x", 0);
	@Unique private final int rp$z = Integer.getInteger("retroperf.bench.z", 0);
	@Unique private final int rp$frames = Integer.getInteger("retroperf.bench.frames", 600);
	@Unique private int rp$warmup = Integer.getInteger("retroperf.bench.warmup", 600);
	@Unique private final boolean rp$pause = Boolean.getBoolean("retroperf.bench.pause");
	@Unique private final int rp$shotAt = Integer.getInteger("retroperf.bench.shot", -1);
	@Unique private final long rp$time = Long.getLong("retroperf.bench.time", 1000L);
	@Unique private final int rp$orbit = Integer.getInteger("retroperf.bench.orbit", 0);
	@Unique private final int rp$mobs = Integer.getInteger("retroperf.bench.mobs", 0);
	/** Eye height above ground. Low values put hills between the camera and distant terrain, which
	 *  is the only situation where occlusion culling can do anything. */
	@Unique private final int rp$eye = Integer.getInteger("retroperf.bench.eye", 24);
	@Unique private final boolean rp$noHud = Boolean.getBoolean("retroperf.bench.nohud");
	/** Pinned yaw. Fractional values give a SUB-PIXEL camera rotation, which is how texture shimmer
	 *  is measured: two runs a fraction of a pixel apart should differ by almost nothing, and the
	 *  amount they do differ IS the aliasing. ~0.082 deg per pixel at the default FOV and width. */
	@Unique private final float rp$yaw = Float.parseFloat(System.getProperty("retroperf.bench.yaw", "0"));
	@Unique private final boolean rp$gui = Boolean.getBoolean("retroperf.bench.gui");
	/**
	 * Pinned pitch, degrees, positive = looking DOWN. Default 32 is the historical vantage and must
	 * stay the default so existing numbers stay comparable.
	 *
	 * <p>NEGATIVE values look up, which is the only way to see the underside of a block whose top
	 * face overhangs its sides -- cactus, and anything else drawn with an inset side. Looking down
	 * at such a junction shows the overhang and hides exactly the thing worth inspecting.
	 */
	@Unique private final float rp$pitch = Float.parseFloat(System.getProperty("retroperf.bench.pitch", "32"));
	/** Blocks ahead of the camera to stage {@code bench.props}. Small values put the props close
	 *  enough that a texture seam is actually resolvable. */
	@Unique private final int rp$propDist = Integer.getInteger("retroperf.bench.propdist", 20);
	@Unique private double rp$angle;
	@Unique private int rp$shotFrame;
	@Unique private long[] rp$periods;
	@Unique private int rp$idx;
	@Unique private long rp$last;
	@Unique private int rp$settle;
	@Unique private boolean rp$teleported;
	@Unique private double rp$eyeY;
	@Unique private boolean rp$done;

	@Inject(method = "onFrameUpdate(F)V", at = @At("HEAD"))
	private void rp$frame(float tickDelta, CallbackInfo ci) {
		if (!this.rp$enabled) {
			return;
		}
		if (this.rp$autoWorld > 0 && this.client.world == null && --this.rp$autoWorld == 0) {
			System.out.println("RETROPERF-BENCH: auto-starting world");
			this.client.interactionManager = new SingleplayerInteractionManager(this.client);
			this.client.startGame("retroperfbench", "retroperfbench", 20260610L);
			this.client.setScreen(null);
			this.client.options.fpsLimit = 0;
			this.rp$settle = 200; // let spawn-area chunks generate before teleport
		}
		if (this.client.world == null || this.rp$done) {
			return;
		}
		this.client.options.fpsLimit = 0; // MAX fps every frame

		if (this.rp$settle > 0) {
			this.rp$settle--;
			return;
		}
		// Teleport ONCE to the vantage, then a second settle to mesh the terrain there.
		if (!this.rp$teleported) {
			this.rp$teleported = true;
			int ground = rp$groundY(this.client.world, this.rp$x, this.rp$z);
			this.rp$eyeY = ground + this.rp$eye;
			this.client.player.setPositionAndAnglesKeepPrevAngles(
				this.rp$x + 0.5, this.rp$eyeY, this.rp$z + 0.5, 0.0F, this.rp$pitch);
			System.out.println("RETROPERF-BENCH: teleported to " + this.rp$x + "," + (ground + this.rp$eye) + "," + this.rp$z);
			rp$spawnMobs(ground);
			rp$stageProps();
			this.rp$settle = 300;
			return;
		}
		// Pin world time. The save persists between runs and beta keeps ticking it, so without
		// this two runs light the scene differently and no pixel comparison is meaningful.
		this.client.world.setTime(this.rp$time);
		// Beta opens the pause menu whenever the window loses focus, which happens constantly
		// when other windows are active. Any screen would land in the shot, so clear it.
		this.client.options.hideHud = this.rp$noHud;
		if (this.rp$gui) {
			// Isolates GUI cost: the inventory is beta's heaviest screen (item icons are each a
			// separate immediate-mode draw) and it renders over the live world.
			if (!(this.client.currentScreen instanceof InventoryScreen)) {
				this.client.setScreen(new InventoryScreen(this.client.player));
			}
		} else if (!this.rp$pause && this.client.currentScreen != null) {
			this.client.setScreen(null);
		}

		// Pin the camera every frame (stable, deterministic view), or fly a circle when orbiting.
		// The orbit is the path that matters for meshing cost: a static camera only builds chunks
		// once at load, so it cannot show re-mesh stutter at all.
		if (this.rp$orbit > 0) {
			this.rp$angle += 0.6;
			double rad = Math.toRadians(this.rp$angle);
			double px = this.rp$x + 0.5 + Math.cos(rad) * this.rp$orbit;
			double pz = this.rp$z + 0.5 + Math.sin(rad) * this.rp$orbit;
			float yaw = (float) (this.rp$angle + 90.0);
			this.client.player.yaw = this.client.player.prevYaw = yaw;
			this.client.player.pitch = this.client.player.prevPitch = 12.0F;
			this.client.player.setPosition(px, this.rp$eyeY, pz);
		} else {
			this.client.player.yaw = this.client.player.prevYaw = this.rp$yaw;
			this.client.player.pitch = this.client.player.prevPitch = this.rp$pitch;
			this.client.player.setPosition(this.rp$x + 0.5, this.rp$eyeY, this.rp$z + 0.5);
		}
		this.client.player.velocityX = this.client.player.velocityY = this.client.player.velocityZ = 0.0;
		if (this.rp$pause) {
			if (!(this.client.currentScreen instanceof GameMenuScreen)) {
				this.client.setScreen(new GameMenuScreen()); // freezes the tick → pure-render measurement
			}
		} else {
			this.client.paused = false;
			if (this.client.currentScreen instanceof GameMenuScreen) {
				this.client.currentScreen = null;
			}
		}

		if (this.rp$shotAt > 0 && ++this.rp$shotFrame >= this.rp$shotAt) {
			String name = net.minecraft.client.Screenshot.take(new java.io.File("."), this.client.displayWidth, this.client.displayHeight);
			System.out.println("RETROPERF-SHOT " + new java.io.File("./screenshots").getAbsolutePath() + " :: " + name);
			this.rp$done = true;
			this.client.scheduleStop();
			return;
		}

		long now = System.nanoTime();
		if (this.rp$last != 0L) {
			if (this.rp$warmup > 0) {
				this.rp$warmup--;
			} else {
				if (this.rp$periods == null) {
					this.rp$periods = new long[this.rp$frames];
				}
				if (this.rp$idx < this.rp$frames) {
					this.rp$periods[this.rp$idx++] = now - this.rp$last;
				}
				if (this.rp$idx >= this.rp$frames) {
					rp$report();
					this.rp$done = true;
				}
			}
		}
		this.rp$last = now;
	}

	/**
	 * Stages a ring of pigs in front of the camera so entity rendering is actually exercised.
	 * Beta draws each model part as its own display-list call, so cost scales with parts on screen,
	 * and the default vantage has almost no entities in it.
	 */
	@Unique
	private void rp$spawnMobs(int ground) {
		if (this.rp$mobs <= 0) {
			return;
		}
		for (int i = 0; i < this.rp$mobs; i++) {
			double angle = 2.0 * Math.PI * i / this.rp$mobs;
			// The camera is 24 blocks up at pitch 32, so it meets the ground ~38 blocks ahead in +Z.
			double px = this.rp$x + 0.5 + Math.cos(angle) * 8.0;
			double pz = this.rp$z + 0.5 + 38.0 + Math.sin(angle) * 8.0;
			PigEntity pig = new PigEntity(this.client.world);
			pig.setPositionAndAngles(px, ground + 1.0, pz, (float) Math.toDegrees(angle), 0.0F);
			this.client.world.spawnEntity(pig);
		}
		System.out.println("RETROPERF-BENCH: spawned " + this.rp$mobs + " pigs");
	}

	/**
	 * Stages the blocks whose rendering is special-cased and therefore easy to break: stacked cacti
	 * (side faces inset 1/16 via {@code Tessellator.translate}, top/bottom drawn full-size), and the
	 * cross-shaped cutouts. The default vantage is open ocean and contains none of them, so these
	 * defects can only be seen by putting them in frame deliberately.
	 *
	 * The platform is placed where the pinned camera is already looking: eye height minus
	 * {@code 0.625 * distance}, which is where a pitch of 32 degrees meets the world.
	 */
	@Unique
	private void rp$stageProps() {
		if (!Boolean.getBoolean("retroperf.bench.props")) {
			return;
		}
		// Blocks ahead of the camera. 20 is far enough that a one-or-two pixel artifact is a
		// one-or-two pixel artifact -- fine for "is it there at all", useless for looking AT it.
		// Drop it to single digits to inspect a texture seam.
		int dist = this.rp$propDist;
		int pz = this.rp$z + dist;
		// Follows the pinned pitch rather than assuming 32 degrees: the platform has to land
		// where the camera is actually looking, or -Dretroperf.bench.pitch aims at empty sky.
		int py = (int) (this.rp$eyeY - Math.tan(Math.toRadians(this.rp$pitch)) * dist);
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				// SANDSTONE UNDERNEATH, and it is not decoration. The platform hangs in mid-air over
				// ocean, and sand FALLS: without a supported base the whole platform turned into
				// falling-block entities during the settle, taking the cacti with it, and the shot
				// came out with no props in it at all. The surface layer still has to be sand,
				// because CactusBlock.canGrow requires sand or cactus directly below.
				this.client.world.setBlock(this.rp$x + dx, py - 1, pz + dz, 24); // sandstone
				this.client.world.setBlock(this.rp$x + dx, py, pz + dz, 12);     // sand
			}
		}
		// Two columns: one 3 tall, one 2 tall, so both a mid-stack and a top junction are in frame.
		// On the FAR edge, so open water sits behind them: a gap at a stacking junction shows as a
		// blue slot. Against the sand platform the same gap is invisible.
		for (int dy = 1; dy <= 3; dy++) {
			this.client.world.setBlock(this.rp$x - 2, py + dy, pz + 4, 81); // cactus
		}
		for (int dy = 1; dy <= 2; dy++) {
			this.client.world.setBlock(this.rp$x + 2, py + dy, pz + 4, 81);
		}
		this.client.world.setBlock(this.rp$x, py, pz, 2);          // grass block
		this.client.world.setBlock(this.rp$x, py + 1, pz, 31, 1);     // tall grass
		this.client.world.setBlock(this.rp$x, py + 1, pz + 2, 31, 2); // fern
		rp$stageLights(py, pz);
		System.out.println("RETROPERF-BENCH: staged props at " + this.rp$x + "," + py + "," + pz);
	}

	/**
	 * {@code -Dretroperf.bench.lights=true} adds emitters and a wall to the prop platform.
	 *
	 * <p>For inspecting BLOCK LIGHT, which the prop platform otherwise cannot show: it is staged in
	 * open ocean under an open sky, so every surface on it is lit by the sun and nothing reveals what
	 * a torch does. Three things are needed to see that, and all three are here:
	 *
	 * <ul>
	 * <li>emitters of DIFFERENT colours, so a renderer that tints them all identically is obvious --
	 *     a torch, a lit redstone torch and a glowstone block;</li>
	 * <li>a WALL between one of them and the rest of the platform, so light that fails to be
	 *     occluded shows up as a lit face that should have been dark;</li>
	 * <li>the whole thing on flat ground, so the falloff is readable as a gradient rather than as
	 *     terrain shape.</li>
	 * </ul>
	 *
	 * <p>Pair it with {@code -Dretroperf.bench.time=18000} -- at noon the sun drowns everything this
	 * is meant to show.
	 */
	@Unique
	private void rp$stageLights(int py, int pz) {
		if (!Boolean.getBoolean("retroperf.bench.lights")) {
			return;
		}
		// A stone wall across the FAR half of the platform, BEYOND the emitters.
		//
		// Beyond, not between: the camera looks along +z, so a wall in front of the torch would show
		// only its own dark face and prove nothing about the light. Behind them, its near face is lit
		// and everything past it must stay black -- which is the occlusion actually being tested.
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = 1; dy <= 3; dy++) {
				this.client.world.setBlock(this.rp$x + dx, py + dy, pz + 2, 1); // stone
			}
		}
		// Three emitters, spread out so their pools do not merge into one wash, and chosen because
		// a renderer that tints all block light alike makes them identical: torch warm orange,
		// glowstone warm white, lit redstone torch red.
		// Metadata 5 on both torches: it is beta's FLOOR-mounted orientation. Placed with the
		// default 0 -- which is not a valid orientation at all -- each torch pops on the first block
		// update, drops as an item and takes the block under it with it, leaving a hole in the
		// platform that reads exactly like a black square of broken lighting.
		this.client.world.setBlock(this.rp$x - 3, py + 1, pz, 50, 5);   // torch
		this.client.world.setBlock(this.rp$x + 3, py + 1, pz, 89);      // glowstone
		this.client.world.setBlock(this.rp$x, py + 1, pz - 1, 76, 5);   // lit redstone torch
        System.out.println("RETROPERF-BENCH: staged lights (torch, glowstone, redstone torch, wall)");
	}

	@Unique
	private static int rp$groundY(World world, int x, int z) {
		for (int y = 120; y > 4; y--) {
			int id = world.getBlockId(x, y, z);
			if (id != 0 && id != 17 && id != 18 && id != 31 && id != 37 && id != 38
					&& id != 39 && id != 40 && id != 78 && id != 81 && id != 83) {
				return y;
			}
		}
		return 64;
	}

	@Unique
	private void rp$report() {
		long[] s = this.rp$periods.clone();
		Arrays.sort(s);
		double medMs = s[s.length / 2] / 1.0e6;
		long sum = 0L;
		for (long p : this.rp$periods) {
			sum += p;
		}
		double avgMs = sum / (double) this.rp$frames / 1.0e6;
		double low1 = s[(int) (s.length * 0.99)] / 1.0e6;
		// Hitch counts describe stutter far better than "worst", which is a single sample and is
		// usually just the first frame that touched ungenerated terrain.
		int over2x = 0;
		int over33 = 0;
		for (long p : this.rp$periods) {
			double ms = p / 1.0e6;
			if (ms > 2.0 * medMs) {
				over2x++;
			}
			if (ms > 33.0) {
				over33++;
			}
		}
		System.out.println(String.format(
			"RETROPERF-BENCH pos=%d,%d orbit=%d frames=%d avg=%.1f fps (%.2f ms)  median=%.2f ms  1%%low=%.1f fps  worst=%.2f ms  hitches[>2xmed=%d >33ms=%d]",
			this.rp$x, this.rp$z, this.rp$orbit, this.rp$frames, 1000.0 / avgMs, avgMs, medMs, 1000.0 / low1,
			s[s.length - 1] / 1.0e6, over2x, over33));
		this.client.scheduleStop();
	}
}
