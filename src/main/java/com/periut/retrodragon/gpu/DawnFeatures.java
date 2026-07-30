package com.periut.retrodragon.gpu;

/**
 * Feature numbers for Dawn's own extensions, which are not the same on every platform this ships to.
 *
 * <p><b>Why this class has to exist.</b> Windows loads a different Dawn build from Linux and macOS
 * -- not by preference but by necessity, because jWebGPU's Windows native exports no {@code wgpu*}
 * symbol for the FFM bindings to find (see {@link WebGPUNatives#resourcePath()}). The two builds
 * agree on all of core WebGPU: 173 struct layouts are identical field-for-field, and every constant
 * in the standard ranges matches. Where they disagree is Dawn's own extension block at
 * {@code 0x0005xxxx}, where 58 constants have shifted because features were added and removed
 * between the two snapshots.
 *
 * <p>Only one of those 58 is read by this mod, so only one is here. The generated bindings in
 * {@code com.periut.webgpu} carry the Linux/macOS numbering, which makes
 * {@code WGPUFeatureName_MultiDrawIndirect()} the WRONG constant to call on Windows -- and wrong in
 * the quietest possible way. It does not fail; it asks the adapter about an unrelated feature and
 * returns a confident false. On a D3D12 adapter that genuinely supports multi-draw indirect
 * (0x50031 is in its feature list) the probe reported "no", and multi-draw would simply never have
 * been used on Windows with nothing anywhere to say why.
 *
 * <p><b>Both numbers are safe to hardcode only because both libraries are pinned</b> --
 * {@code dawn_version} and {@code dawn_windows_tag} in gradle.properties. Bumping either can shift
 * its extension block again, so re-check with {@code ./gradlew featureProbe} on that platform after
 * a bump and correct the value here. {@code ./gradlew smokeTest} prints the adapter's raw feature
 * list, which is what to diff against.
 *
 * <p>This whole class is a workaround for a packaging gap, not a design. If jWebGPU ever exports the
 * C API from its Windows native, Windows can go back to the same Dawn as everything else and this
 * file should be deleted outright rather than extended.
 */
public final class DawnFeatures {
	/** {@code WGPUFeatureName_MultiDrawIndirect} in build-dawn {@code 2026-07-26} (Windows). */
	private static final int MULTI_DRAW_INDIRECT_WINDOWS = 0x50031;

	/** The same feature in jWebGPU 0.3.4's Dawn, which is what the generated bindings encode. */
	private static final int MULTI_DRAW_INDIRECT_OTHER = 0x50034;

	private DawnFeatures() {
	}

	/**
	 * {@code WGPUFeatureName_MultiDrawIndirect} for the Dawn actually loaded.
	 *
	 * <p>Use this rather than {@code webgpu_h.WGPUFeatureName_MultiDrawIndirect()}, which is right on
	 * two platforms out of three.
	 */
	public static int multiDrawIndirect() {
		return WebGPUNatives.isWindows() ? MULTI_DRAW_INDIRECT_WINDOWS : MULTI_DRAW_INDIRECT_OTHER;
	}
}
