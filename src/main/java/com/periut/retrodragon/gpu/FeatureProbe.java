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
	/** {@code WGPUFeatureName_MultiDrawIndirect}, and its indexed sibling. */
	private static final int MULTI_DRAW_INDIRECT = 0x50034;
	private static final int MULTI_DRAW_INDEXED_INDIRECT = 0x50035;

	private FeatureProbe() {
	}

	public static void main(String[] args) {
		try (WebGPUContext ctx = WebGPUContext.create()) {
			System.out.println("backend = " + ctx.backendName());
			java.util.List<Integer> features = ctx.features();
			System.out.println("count   = " + features.size());
			for (int feature : features) {
				System.out.printf("  0x%05X%n", feature);
			}
			System.out.println("MultiDrawIndirect        (0x50034) = "
				+ ctx.hasFeature(MULTI_DRAW_INDIRECT));
			System.out.println("MultiDrawIndexedIndirect (0x50035) = "
				+ ctx.hasFeature(MULTI_DRAW_INDEXED_INDIRECT));
		}
	}
}
