package com.periut.retrodragon.gpu;

/**
 * WebGPU bitflag constants.
 *
 * <p>Dawn declares these as {@code static const} globals rather than {@code #define}s or enum
 * members, and jextract emits neither -- so unlike every other constant in the API they are not on
 * {@code webgpu_h} and have to be transcribed. Values are from {@code dawn/webgpu.h}; the line
 * numbers are noted so a Dawn upgrade can be diffed against them.
 *
 * <p>All are 64-bit: the corresponding {@code WGPUTextureUsage} / {@code WGPUBufferUsage} typedefs
 * are {@code uint64_t}, and truncating to int silently drops the high flags.
 */
public final class Flags {
	private Flags() {
	}

	// --- WGPUTextureUsage (webgpu.h ~1207-1213) ---
	public static final long TEXTURE_USAGE_NONE = 0x0L;
	public static final long TEXTURE_USAGE_COPY_SRC = 0x1L;
	public static final long TEXTURE_USAGE_COPY_DST = 0x2L;
	public static final long TEXTURE_USAGE_TEXTURE_BINDING = 0x4L;
	public static final long TEXTURE_USAGE_STORAGE_BINDING = 0x8L;
	public static final long TEXTURE_USAGE_RENDER_ATTACHMENT = 0x10L;

	// --- WGPUColorWriteMask (webgpu.h ~1181-1185) ---
	public static final long COLOR_WRITE_RED = 0x1L;
	public static final long COLOR_WRITE_GREEN = 0x2L;
	public static final long COLOR_WRITE_BLUE = 0x4L;
	public static final long COLOR_WRITE_ALPHA = 0x8L;
	public static final long COLOR_WRITE_ALL = 0xFL;

	// --- WGPUMapMode (webgpu.h ~1193-1196) ---
	public static final long MAP_MODE_NONE = 0x0L;
	public static final long MAP_MODE_READ = 0x1L;
	public static final long MAP_MODE_WRITE = 0x2L;

	// --- WGPUShaderStage (webgpu.h ~1199-1203) ---
	public static final long SHADER_STAGE_NONE = 0x0L;
	public static final long SHADER_STAGE_VERTEX = 0x1L;
	public static final long SHADER_STAGE_FRAGMENT = 0x2L;
	public static final long SHADER_STAGE_COMPUTE = 0x4L;

	// --- WGPUBufferUsage --- needed from phase 4 (vertex/uniform/indirect buffers)
	public static final long BUFFER_USAGE_MAP_READ = 0x1L;
	public static final long BUFFER_USAGE_MAP_WRITE = 0x2L;
	public static final long BUFFER_USAGE_COPY_SRC = 0x4L;
	public static final long BUFFER_USAGE_COPY_DST = 0x8L;
	public static final long BUFFER_USAGE_INDEX = 0x10L;
	public static final long BUFFER_USAGE_VERTEX = 0x20L;
	public static final long BUFFER_USAGE_UNIFORM = 0x40L;
	public static final long BUFFER_USAGE_STORAGE = 0x80L;
	public static final long BUFFER_USAGE_INDIRECT = 0x100L;
}
