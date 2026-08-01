package com.periut.retrodragon.mixin;

import net.minecraft.client.render.item.HeldItemRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * MC-73186: the gaps you can see through at the corners of a held item.
 *
 * <h2>The artefact</h2>
 *
 * A held sword, pickaxe or fishing rod is not a sprite -- beta extrudes the 2D icon into a slab and
 * walls the sides in, which is what gives it depth. At the corners of that slab you can see straight
 * through it, and at concave corners you see a colour from the MIDDLE of the icon on a face that
 * should be showing its edge. Mojang tracked this as
 * <a href="https://bugs.mojang.com/browse/MC-73186">MC-73186</a> for eleven years and fixed it in
 * 25w44a.
 *
 * <h2>The cause: texture zoom</h2>
 *
 * beta insets every item icon's UV rectangle by a hundredth of a texel -- the sprite occupies
 * columns {@code 0 .. 15.99} of its 16-wide cell rather than {@code 0 .. 16}. That inset is an
 * anti-bleed measure: it keeps a filtered sample near the edge of one icon from reaching into the
 * neighbouring icon on the shared 256x256 sheet.
 *
 * <p>It also means the icon's texture is very slightly SMALLER than the geometry it is stretched
 * over, and the extrusion is where that stops being invisible. The side walls are placed at exact
 * sixteenths of the slab -- {@code x = k/16} -- while the texel boundaries they are supposed to line
 * up with sit at {@code k/16 x 16/15.99}. The two drift apart across the icon, so along a silhouette
 * the wall no longer meets the front face's last opaque texel: there is a sliver that neither quad
 * covers, and the alpha test discards what little does land there. Straight through the item.
 *
 * <p>At a corner two of those slivers meet at right angles, which is why corners are where it shows.
 * And a hole in the near surface exposes the INSIDE of the far wall, which is textured with its own
 * side's texel -- some colour from the interior of the icon, appearing on an outside edge. That is
 * the second half of what this looks like, and it is the same single cause.
 *
 * <h2>The fix</h2>
 *
 * Drop the zoom: let each quad's texture cover exactly 100% of its own cell. The walls then land on
 * real texel boundaries, the faces meet with nothing between them, and the model is sealed. This is
 * what Mojang did in 25w44a and what the Model Gap Fix mod does for modern versions.
 *
 * <p><b>Why removing the inset is safe here and is not always.</b> The inset only ever bought
 * anything under a filter that reads more than one texel -- linear sampling or a mip chain. Item
 * icons in RetroDragon have neither: {@code /gui/items.png} is sampled nearest, and
 * {@code TextureStore} mipmaps the block atlas and nothing else on purpose. With a nearest tap at an
 * exact texel centre there is no neighbouring texel in the result to bleed, so the inset is
 * protecting against something that cannot happen while costing a visible hole in every tool.
 *
 * <p>Scoped to {@code renderItem} deliberately. {@code HeldItemRenderer} uses the same constant in
 * {@code renderTexturedOverlay} and {@code renderFireOverlay}, which are flat full-screen quads with
 * no seams to open and no reason to change.
 *
 * <p>Geometry and UVs only, so it is one fix for both backends -- the WebGPU renderer and the GL one
 * receive the corrected vertices identically.
 *
 * <p><b>Not applied when StationAPI's Arsenic renderer is installed.</b> Arsenic {@code @Overwrite}s
 * {@code renderItem} down to a single call into its own item renderer, so beta's extrusion and the
 * constant patched here do not exist in that build of the method. {@code GlPlugin.shouldApplyMixin}
 * makes that call; see there for why leaving it to Mixin is not an option.
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemGapMixin {

	/**
	 * beta's 16-texel icon cell, written as {@code 15.99}. Both occurrences in this method are the
	 * zoom -- one for U, one for V -- so no ordinal is needed.
	 */
	@ModifyConstant(
		method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;)V",
		constant = @Constant(floatValue = 15.99F))
	private float retrodragon$unzoomItemIcon(float zoomed) {
		return 16.0F;
	}
}
