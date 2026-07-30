package com.periut.retrodragon.window.mixin.lwjgl3;

import java.nio.IntBuffer;

import com.periut.retrodragon.window.lwjgl3compat.annotations.Public;
import org.lwjgl.LWJGLException;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import static org.lwjgl.openal.AL.createCapabilities;

// Used to extend LWJGL's AL class with legacy compatibility methods
@Mixin(AL.class)
public abstract class ALMixin {

	@Unique
	private static long _contextPtr;
	@Unique
	private static long _devicePtr;
	@Unique
	private static boolean _created;

	@Unique
	@Public
	private static boolean isCreated() {
		return _created;
	}

	@Unique
	@Public
	private static void create(String deviceArguments, int contextFrequency, int contextRefresh, boolean contextSynchronized) throws LWJGLException {
		create(deviceArguments, contextFrequency, contextRefresh, contextSynchronized, true);
	}

	@Unique
	@Public
	private static void create(String deviceArguments, int contextFrequency, int contextRefresh, boolean contextSynchronized, boolean openDevice) throws LWJGLException {
		if (_created) {
			// RetroCenter: this AL class is shared between the hub and
			// in-process child instances. A second instance's create() means
			// "give me a working context" -- the live shared one is exactly
			// that, so reuse it instead of failing the caller into silence.
			return;
		} else {
			init(deviceArguments, contextFrequency, contextRefresh, contextSynchronized, openDevice);
			_created = true;
		}
	}

	@Unique
	private static void init(String deviceArguments, int contextFrequency, int contextRefresh, boolean contextSynchronized, boolean openDevice) throws LWJGLException {
		try {
			if (openDevice) {
				_devicePtr = ALC10.alcOpenDevice(deviceArguments);
				if (_devicePtr == -1L) {
					throw new LWJGLException("Could not open ALC device");
				}

				ALCCapabilities deviceCaps = ALC.createCapabilities(_devicePtr);
				if (contextFrequency == -1) {
					_contextPtr = ALC10.alcCreateContext(_devicePtr, (IntBuffer) null);
				} else {
					try (MemoryStack stack = MemoryStack.stackPush()) {
						_contextPtr = ALC10.alcCreateContext(_devicePtr, createAttributeList(contextFrequency, contextRefresh, contextSynchronized ? 1 : 0, stack));
					}
				}

				ALC10.alcMakeContextCurrent(_contextPtr);
				createCapabilities(deviceCaps);
			}

		} catch (LWJGLException var7) {
			exit();
			throw var7;
		}
	}

	@Unique
	@Public
	private static void create() throws LWJGLException {
		create(null, 44100, 60, false);
	}

	@Unique
	private static IntBuffer createAttributeList(int contextFrequency, int contextRefresh, int contextSynchronized, MemoryStack stack) {
		IntBuffer buffer = stack.callocInt(7);
		buffer.put(0, 4103);
		buffer.put(1, contextFrequency);
		buffer.put(2, 4104);
		buffer.put(3, contextRefresh);
		buffer.put(4, 4105);
		buffer.put(5, contextSynchronized);
		buffer.put(6, 0);
		return buffer;
	}

	@Unique
	@Public
	private static void exit() {
		if (_contextPtr != -1L) {
			ALC10.alcMakeContextCurrent(0L);
			ALC10.alcDestroyContext(_contextPtr);
			_contextPtr = -1L;
		}

		if (_devicePtr != -1L) {
			ALC10.alcCloseDevice(_devicePtr);
			_devicePtr = -1L;
		}

		_created = false;
	}
}
