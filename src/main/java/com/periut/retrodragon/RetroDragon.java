package com.periut.retrodragon;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.ornithemc.osl.entrypoints.api.ModInitializer;

public class RetroDragon implements ModInitializer {

	public static final Logger LOGGER = LogManager.getLogger("RetroDragon");

	@Override
	public void init() {
		// Before anything creates a window: a WebGPU surface needs one with no GL context, and that
		// is decided at SDL_CreateWindow, not afterwards.
		com.periut.retrodragon.render.RenderBackend.select();
		LOGGER.info("RetroDragon loaded");
	}
}
