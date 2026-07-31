package com.periut.retrodragon;

import com.periut.retrodragon.render.RenderBackend;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Chooses the render backend before anything can create a window.
 *
 * <p>This cannot wait for a mod-initialiser entrypoint. A WebGPU surface needs a window built
 * WITHOUT a GL context, so the decision has to be made before {@code SDL_CreateWindow} -- and beta
 * creates its window inside {@code Minecraft.init()}, which runs before any init entrypoint does.
 * Selecting there produced a GL 2.1 context and a backend that then refused to select itself, which
 * is a correct outcome but not the intended one.
 *
 * <p>That ordering is also why this is now the ONLY place the backend is chosen: the OSL {@code
 * init} entrypoint that used to call it as well ran strictly later and could only ever re-confirm
 * the answer. See {@link RetroDragon}.
 *
 * <p>{@code preLaunch} runs before the game's main class is even loaded, which is early enough.
 */
public class RetroDragonPreLaunch implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		RenderBackend.select();
		// The one line the OSL init entrypoint used to contribute. It said nothing select() had not
		// already logged, but it is the marker a bug report looks for to see the mod loaded at all.
		RetroDragon.LOGGER.info("RetroDragon loaded");
	}
}
