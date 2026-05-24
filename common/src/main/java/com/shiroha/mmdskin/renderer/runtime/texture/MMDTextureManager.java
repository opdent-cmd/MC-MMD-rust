/* 文件职责：管理纹理解码、上传与可回收缓存，并保持 native 访问按需触发。*/
package com.shiroha.mmdskin.renderer.runtime.texture;

import com.shiroha.mmdskin.NativeFunc;
import com.shiroha.mmdskin.config.ConfigManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL46C;
import org.lwjgl.system.MemoryUtil;

/**
 * MMD 纹理管理器。
 */
public class MMDTextureManager {
    private static final Logger logger = LogManager.getLogger();
    private static final int RGB_CHANNELS = 3;
    private static final int RGBA_CHANNELS = 4;
    private static NativeFunc nf;

    private static volatile Map<String, Texture> textures;

    private static final Map<String, Texture> pendingRelease = new ConcurrentHashMap<>();

    private static final Map<String, PredecodedTexture> predecodedTextures = new ConcurrentHashMap<>();
    private static final Object predecodedTextureLock = new Object();

    private static final long TEXTURE_TTL_MS = 60_000;

    public static void Init() {
        nf = null;
        textures = new ConcurrentHashMap<>();
        pendingRelease.clear();
    }

    private static NativeFunc nativeFunc() {
        NativeFunc local = nf;
        if (local == null) {
            local = NativeFunc.GetInst();
            nf = local;
        }
        return local;
    }

    public static void preloadTexture(String filename) {

        Map<String, Texture> localTextures = textures;
        if (localTextures == null) return;
        if (localTextures.containsKey(filename) || pendingRelease.containsKey(filename)
                || predecodedTextures.containsKey(filename)) {
            return;
        }

        NativeFunc localNf = nativeFunc();
        long nfTex = localNf.LoadTexture(filename);
        if (nfTex == 0) {
            return;
        }

        try {
            int x = localNf.GetTextureX(nfTex);
            int y = localNf.GetTextureY(nfTex);
            long texData = localNf.GetTextureData(nfTex);
            boolean hasAlpha = localNf.TextureHasAlpha(nfTex);

            int texSize = validateTextureSize(filename, x, y, hasAlpha);
            if (texSize <= 0) {
                return;
            }
            ByteBuffer pixelBuffer = MemoryUtil.memAlloc(texSize);
            localNf.CopyDataToByteBuffer(pixelBuffer, texData, texSize);
            pixelBuffer.rewind();

            PredecodedTexture predecoded = new PredecodedTexture();
            predecoded.pixelData = pixelBuffer;
            predecoded.width = x;
            predecoded.height = y;
            predecoded.hasAlpha = hasAlpha;

            synchronized (predecodedTextureLock) {
                PredecodedTexture existing = predecodedTextures.putIfAbsent(filename, predecoded);
                if (existing != null) {
                    MemoryUtil.memFree(pixelBuffer);
                }
            }
        } finally {
            localNf.DeleteTexture(nfTex);
        }
    }

    public static void clearPreloaded() {

        synchronized (predecodedTextureLock) {
            for (PredecodedTexture p : predecodedTextures.values()) {
                if (p.pixelData != null) {
                    MemoryUtil.memFree(p.pixelData);
                    p.pixelData = null;
                }
            }
            predecodedTextures.clear();
        }
    }

    public static Texture GetTexture(String filename) {
        Map<String, Texture> localTextures = textures;
        if (localTextures == null) {
            return null;
        }

        Texture result = localTextures.get(filename);
        if (result != null) {
            return result;
        }

        result = pendingRelease.remove(filename);
        if (result != null) {
            result.refCount.set(0);
            localTextures.put(filename, result);
            return result;
        }

        PredecodedTexture predecoded = takePredecodedTexture(filename);
        if (predecoded != null) {
            result = uploadPredecodedTexture(filename, predecoded);
            if (result != null) {
                localTextures.put(filename, result);
            }
            return result;
        }

        NativeFunc localNf = nativeFunc();
        long nfTex = localNf.LoadTexture(filename);
        if (nfTex == 0) {
            return null;
        }
        int x = localNf.GetTextureX(nfTex);
        int y = localNf.GetTextureY(nfTex);
        long texData = localNf.GetTextureData(nfTex);
        boolean hasAlpha = localNf.TextureHasAlpha(nfTex);
        int texSize = validateTextureSize(filename, x, y, hasAlpha);
        if (texSize <= 0) {
            localNf.DeleteTexture(nfTex);
            return null;
        }

        ByteBuffer texBuffer = MemoryUtil.memAlloc(texSize);
        try {
            localNf.CopyDataToByteBuffer(texBuffer, texData, texSize);
            texBuffer.rewind();
            result = createTextureFromPixels(filename, x, y, hasAlpha, texBuffer, texSize);
        } finally {
            MemoryUtil.memFree(texBuffer);
            localNf.DeleteTexture(nfTex);
        }
        if (result != null) {
            localTextures.put(filename, result);
        }
        return result;
    }

    private static Texture uploadPredecodedTexture(String filename, PredecodedTexture predecoded) {
        try {
            int texSize = validateTextureSize(filename, predecoded.width, predecoded.height, predecoded.hasAlpha);
            if (texSize <= 0) {
                return null;
            }
            return createTextureFromPixels(
                filename,
                predecoded.width,
                predecoded.height,
                predecoded.hasAlpha,
                predecoded.pixelData,
                texSize
            );
        } finally {
            if (predecoded.pixelData != null) {
                MemoryUtil.memFree(predecoded.pixelData);
                predecoded.pixelData = null;
            }
        }
    }

    private static PredecodedTexture takePredecodedTexture(String filename) {
        synchronized (predecodedTextureLock) {
            return predecodedTextures.remove(filename);
        }
    }

    private static Texture createTextureFromPixels(String filename,
                                                   int width,
                                                   int height,
                                                   boolean hasAlpha,
                                                   ByteBuffer pixelData,
                                                   int expectedSize) {
        ByteBuffer uploadView = prepareUploadView(filename, pixelData, expectedSize);
        if (uploadView == null) {
            return null;
        }

        int tex = GL46C.glGenTextures();
        try {
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, tex);
            configureUnpackState(hasAlpha);
            uploadTexturePixels(width, height, hasAlpha, uploadView);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAX_LEVEL, 0);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MIN_FILTER, GL46C.GL_LINEAR);
            GL46C.glTexParameteri(GL46C.GL_TEXTURE_2D, GL46C.GL_TEXTURE_MAG_FILTER, GL46C.GL_LINEAR);
        } catch (RuntimeException e) {
            GL46C.glDeleteTextures(tex);
            logger.error("纹理上传失败: {}", filename, e);
            return null;
        } finally {
            resetUnpackState();
            GL46C.glBindTexture(GL46C.GL_TEXTURE_2D, 0);
        }

        Texture result = new Texture();
        result.tex = tex;
        result.hasAlpha = hasAlpha;
        result.vramSize = (long) expectedSize;
        return result;
    }

    private static ByteBuffer prepareUploadView(String filename, ByteBuffer pixelData, int expectedSize) {
        if (pixelData == null) {
            logger.warn("纹理像素缓冲区为空: {}", filename);
            return null;
        }
        if (!pixelData.isDirect()) {
            logger.warn("纹理像素缓冲区不是 direct buffer: {}", filename);
            return null;
        }
        if (pixelData.capacity() < expectedSize) {
            logger.warn("纹理像素缓冲区容量不足: {} expected={} actual={}", filename, expectedSize, pixelData.capacity());
            return null;
        }

        ByteBuffer uploadView = pixelData.duplicate();
        uploadView.clear();
        uploadView.limit(expectedSize);
        return uploadView;
    }

    private static void configureUnpackState(boolean hasAlpha) {
        GL46C.glPixelStorei(GL46C.GL_UNPACK_ALIGNMENT, hasAlpha ? RGBA_CHANNELS : 1);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_ROW_LENGTH, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_ROWS, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_PIXELS, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_IMAGES, 0);
    }

    private static void resetUnpackState() {
        GL46C.glPixelStorei(GL46C.GL_UNPACK_ALIGNMENT, RGBA_CHANNELS);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_ROW_LENGTH, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_ROWS, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_PIXELS, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL46C.glPixelStorei(GL46C.GL_UNPACK_SKIP_IMAGES, 0);
    }

    private static void uploadTexturePixels(int width, int height, boolean hasAlpha, ByteBuffer uploadView) {
        if (hasAlpha) {
            GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGBA, width, height, 0,
                GL46C.GL_RGBA, GL46C.GL_UNSIGNED_BYTE, uploadView);
            return;
        }

        GL46C.glTexImage2D(GL46C.GL_TEXTURE_2D, 0, GL46C.GL_RGB, width, height, 0,
            GL46C.GL_RGB, GL46C.GL_UNSIGNED_BYTE, uploadView);
    }

    private static int validateTextureSize(String filename, int width, int height, boolean hasAlpha) {
        if (width <= 0 || height <= 0) {
            logger.warn("纹理尺寸非法: {} width={} height={}", filename, width, height);
            return -1;
        }

        long channelCount = hasAlpha ? RGBA_CHANNELS : RGB_CHANNELS;
        long textureBytes = (long) width * height * channelCount;
        if (textureBytes <= 0 || textureBytes > Integer.MAX_VALUE) {
            logger.warn("纹理字节数非法: {} width={} height={} channels={} bytes={}",
                filename, width, height, channelCount, textureBytes);
            return -1;
        }

        return (int) textureBytes;
    }

    public static void addRef(String filename) {
        Texture tex = textures.get(filename);
        if (tex != null) {
            tex.refCount.incrementAndGet();
        }
    }

    public static void release(String filename) {
        if (filename == null || textures == null) return;
        textures.compute(filename, (key, tex) -> {
            if (tex == null) return null;
            int remaining = tex.refCount.decrementAndGet();
            if (remaining <= 0) {
                tex.refCount.set(0);
                tex.lastReleaseTime = System.currentTimeMillis();
                pendingRelease.put(key, tex);
                return null;
            }
            return tex;
        });
    }

    public static void releaseAll(List<String> filenames) {
        if (filenames == null) return;
        for (String filename : filenames) {
            release(filename);
        }
    }

    public static void tick() {
        if (pendingRelease.isEmpty()) return;

        long now = System.currentTimeMillis();
        long budgetBytes = ConfigManager.getTextureCacheBudgetMB() * 1024L * 1024L;

        List<String> expired = new ArrayList<>();
        for (var entry : pendingRelease.entrySet()) {
            if (now - entry.getValue().lastReleaseTime > TEXTURE_TTL_MS) {
                expired.add(entry.getKey());
            }
        }
        for (String key : expired) {
            Texture tex = pendingRelease.remove(key);
            if (tex != null) {
                deleteGlTexture(tex);
            }
        }

        long pendingVram = getPendingReleaseVram();
        if (pendingVram > budgetBytes && !pendingRelease.isEmpty()) {
            evictByLRU(pendingVram, budgetBytes);
        }
    }

    private static synchronized void evictByLRU(long currentVram, long budgetBytes) {
        if (pendingRelease.isEmpty()) return;

        List<Map.Entry<String, Texture>> sorted = new ArrayList<>(pendingRelease.entrySet());
        sorted.sort((a, b) -> Long.compare(a.getValue().lastReleaseTime, b.getValue().lastReleaseTime));

        long remaining = currentVram;
        int evicted = 0;
        for (var entry : sorted) {
            if (remaining <= budgetBytes) break;
            Texture tex = pendingRelease.remove(entry.getKey());
            if (tex != null) {
                remaining -= tex.vramSize;
                deleteGlTexture(tex);
                evicted++;
            }
        }
        if (evicted > 0) {
        }
    }

    private static void deleteGlTexture(Texture tex) {
        if (tex != null && tex.tex > 0) {
            GL46C.glDeleteTextures(tex.tex);
            tex.tex = 0;
        }
    }

    public static void Cleanup() {
        if (textures != null) {
            int count = textures.size();
            for (Texture tex : textures.values()) {
                deleteGlTexture(tex);
            }
            textures.clear();
        }
        int pendingCount = pendingRelease.size();
        for (Texture tex : pendingRelease.values()) {
            deleteGlTexture(tex);
        }
        pendingRelease.clear();
    }

    public static void DeleteTexture(String filename) {
        if (textures != null) {
            Texture tex = textures.remove(filename);
            deleteGlTexture(tex);
        }
        Texture pending = pendingRelease.remove(filename);
        deleteGlTexture(pending);
    }

    public static class Texture {
        public int tex;
        public boolean hasAlpha;

        public long vramSize;

        final AtomicInteger refCount = new AtomicInteger(0);

        volatile long lastReleaseTime;
    }

    public static long getTotalTextureVram() {
        if (textures == null) return 0;
        long total = 0;
        for (Texture tex : textures.values()) {
            total += tex.vramSize;
        }
        return total;
    }

    public static int getTextureCount() {
        return textures != null ? textures.size() : 0;
    }

    public static int getPendingReleaseCount() {
        return pendingRelease.size();
    }

    public static long getPendingReleaseVram() {
        long total = 0;
        for (Texture tex : pendingRelease.values()) {
            total += tex.vramSize;
        }
        return total;
    }

    static class PredecodedTexture {
        ByteBuffer pixelData;
        int width;
        int height;
        boolean hasAlpha;
    }
}
