package com.periut.retrodragon.gpu;

/**
 * Every WebGPU feature this adapter advertises. {@code ./gradlew featureProbe}.
 *
 * <p>Exists because "is X available?" is otherwise answered from documentation, and Dawn's answer
 * depends on the build, the backend and the toggles the context was created with -- none of which the
 * documentation knows about. {@link WebGPUContext} chains a {@code WGPUDawnTogglesDescriptor}
 * enabling {@code allow_unsafe_apis} onto BOTH the instance descriptor and the adapter request,
 * which is what takes this list from 38 entries to 50; either one alone changes nothing.
 *
 * <p>Multi-draw is called out by name because it is the feature this project keeps being asked
 * about. It is defined in the generated header, its implementation is present in the shipped native
 * -- the Metal converter and all -- and it is still not registered for this backend. The honest answer
 * is a run of this rather than an opinion, and it takes a second.
 */
public final class FeatureProbe {
	private FeatureProbe() {
	}

	public static void main(String[] args) {
		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("backend = " + ctx.apiSummary());
			java.util.List<Integer> features = ctx.features();
			System.out.println("count   = " + features.size());
			for (int feature : features) {
				System.out.printf("  0x%05X%n", feature);
			}
			// Via DawnFeatures, not the hardcoded 0x50034 this used to carry: that number is only
			// correct for the Dawn on Linux and macOS, and on Windows it silently probed something
			// else. The value is printed because it is the thing to check against the list above.
			int multiDraw = DawnFeatures.multiDrawIndirect();
			System.out.printf("MultiDrawIndirect        (0x%05X) = %s%n",
				multiDraw, ctx.hasFeature(multiDraw));
			// There is deliberately no MultiDrawIndexedIndirect line. This printed one for 0x50035,
			// which is not an indexed sibling in either shipped Dawn -- in jWebGPU 0.3.4's numbering
			// that value is DawnTexelCopyBufferRowAlignment, so the answer was about an unrelated
			// feature. Neither header declares any indexed multi-draw feature; the single
			// MultiDrawIndirect feature is what guards both entry points.
		}
	}
}
