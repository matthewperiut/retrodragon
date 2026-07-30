package org.lwjgl.opengl;

import java.nio.ByteBuffer;

import org.lwjgl.glfw.GLFWNativeNSGL;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.macosx.ObjCRuntime;

/**
 * macOS-specific display helpers. This class is only loaded on macOS,
 * so it's safe to reference macOS-only LWJGL classes directly.
 */
final class MacOSDisplayHelper {
	private static long cglContext;

	private MacOSDisplayHelper() {}

	/**
	 * Opt the NSApplication into system appearance inheritance by calling
	 * [[NSApplication sharedApplication] setAppearance:nil].
	 * Newer macOS versions hard-assert (NSInternalInconsistencyException in
	 * ViewBridge) when appearance changes happen off the main thread, and
	 * under glfw_async we usually run on the game thread -- so the call is
	 * bounced to the main thread when needed.
	 */
	static void initAppAppearance() {
		try {
			long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

			long nsAppClass = ObjCRuntime.objc_getClass("NSApplication");
			long selSharedApp = ObjCRuntime.sel_getUid("sharedApplication");
			long nsApp = JNI.invokePPP(nsAppClass, selSharedApp, objc_msgSend);
			if (nsApp == 0L) return;

			// [NSApp setAppearance:nil] -- nil means inherit system appearance
			long selSetAppearance = ObjCRuntime.sel_getUid("setAppearance:");
			if (isMainThread(objc_msgSend)) {
				JNI.invokePPPP(nsApp, selSetAppearance, 0L, objc_msgSend);
			} else {
				performOnMainThread(nsApp, selSetAppearance, 0L, objc_msgSend);
			}
		} catch (Exception e) {
			System.err.println("[Display] Failed to init macOS app appearance: " + e.getMessage());
		}
	}

	/** [NSThread isMainThread] */
	private static boolean isMainThread(long objc_msgSend) {
		long nsThreadClass = ObjCRuntime.objc_getClass("NSThread");
		long selIsMainThread = ObjCRuntime.sel_getUid("isMainThread");
		return JNI.invokePPP(nsThreadClass, selIsMainThread, objc_msgSend) != 0L;
	}

	/**
	 * [target performSelectorOnMainThread:selector withObject:object
	 * waitUntilDone:NO] -- fire-and-forget so we never deadlock if the main
	 * thread isn't pumping a runloop.
	 */
	private static void performOnMainThread(long target, long selector, long object, long objc_msgSend) {
		long selPerform = ObjCRuntime.sel_getUid("performSelectorOnMainThread:withObject:waitUntilDone:");
		JNI.invokePPPPPP(target, selPerform, selector, object, 0L, objc_msgSend);
	}

	/**
	 * Get the CGL context from a GLFW window and lock it for the calling thread.
	 * This prevents the macOS compositor from accessing the GL surface concurrently,
	 * avoiding SIGSEGV in AppleMetalOpenGLRenderer during window resize.
	 */
	static void lockCGLContext(long windowHandle) {
		try {
			long nsgl = GLFWNativeNSGL.glfwGetNSGLContext(windowHandle);
			if (nsgl == 0L) return;

			long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long selCGLContextObj = ObjCRuntime.sel_getUid("CGLContextObj");
			cglContext = JNI.invokePPP(nsgl, selCGLContextObj, objc_msgSend);

			if (cglContext != 0L) {
				CGL.CGLLockContext(cglContext);
			}
		} catch (Exception e) {
			System.err.println("[Display] Failed to lock CGL context: " + e.getMessage());
		}
	}

	static void unlockCGLContext() {
		if (cglContext != 0L) {
			CGL.CGLUnlockContext(cglContext);
		}
	}

	static void relockCGLContext() {
		if (cglContext != 0L) {
			CGL.CGLLockContext(cglContext);
		}
	}

	/**
	 * Sets the Dock icon via NSApplication from PNG-encoded bytes.
	 * glfwSetWindowIcon is a no-op on macOS, so this is the only way
	 * to set the app icon at runtime.
	 */
	static void setDockIcon(byte[] pngBytes) {
		try {
			// Write PNG to a temp file so we can use [NSImage initWithContentsOfFile:]
			java.nio.file.Path tmpIcon = java.nio.file.Files.createTempFile("retrodragon-icon", ".png");
			java.nio.file.Files.write(tmpIcon, pngBytes);
			tmpIcon.toFile().deleteOnExit();

			long objc_msgSend = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
			long selAlloc = ObjCRuntime.sel_getUid("alloc");

			// Create NSString for the file path
			long nsStringClass = ObjCRuntime.objc_getClass("NSString");
			long selStringWithUTF8 = ObjCRuntime.sel_getUid("stringWithUTF8String:");
			ByteBuffer pathBuf = MemoryUtil.memASCII(tmpIcon.toAbsolutePath().toString(), true);
			long nsPath = JNI.invokePPPP(nsStringClass, selStringWithUTF8, MemoryUtil.memAddress(pathBuf), objc_msgSend);
			MemoryUtil.memFree(pathBuf);
			if (nsPath == 0L) return;

			// [[NSImage alloc] initWithContentsOfFile:nsPath]
			long nsImageClass = ObjCRuntime.objc_getClass("NSImage");
			long nsImage = JNI.invokePPP(nsImageClass, selAlloc, objc_msgSend);
			long selInitWithFile = ObjCRuntime.sel_getUid("initWithContentsOfFile:");
			nsImage = JNI.invokePPPP(nsImage, selInitWithFile, nsPath, objc_msgSend);
			if (nsImage == 0L) return;

			// [[NSApplication sharedApplication] setApplicationIconImage:nsImage]
			long nsAppClass = ObjCRuntime.objc_getClass("NSApplication");
			long selSharedApp = ObjCRuntime.sel_getUid("sharedApplication");
			long nsApp = JNI.invokePPP(nsAppClass, selSharedApp, objc_msgSend);
			long selSetIcon = ObjCRuntime.sel_getUid("setApplicationIconImage:");
			if (isMainThread(objc_msgSend)) {
				JNI.invokePPPP(nsApp, selSetIcon, nsImage, objc_msgSend);
			} else {
				// Same main-thread-only AppKit rule as setAppearance:.
				performOnMainThread(nsApp, selSetIcon, nsImage, objc_msgSend);
			}

			System.out.println("[RetroDragon] Set macOS Dock icon");
		} catch (Exception e) {
			System.err.println("[Display] Failed to set macOS Dock icon: " + e.getMessage());
		}
	}
}
