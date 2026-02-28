package com.vnc.util;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.NativeLongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class JpegCodec {

    private static final Logger log = LoggerFactory.getLogger(JpegCodec.class);

    private static final int TJPF_RGB = 0;
    private static final int TJSAMP_420 = 2;

    private static final LibTurboJpeg TJ;

    static {
        TJ = loadTurboJpeg();
        if (TJ != null) {
            log.info("TurboJPEG native acceleration enabled");
        } else {
            log.info("TurboJPEG not available — using ImageIO fallback");
        }
    }

    private static final ThreadLocal<Pointer> COMPRESSOR = ThreadLocal.withInitial(() ->
            TJ != null ? TJ.tjInitCompress() : null
    );

    private JpegCodec() {
    }

    public static byte[] encode(BufferedImage image, float quality) {
        Pointer comp = COMPRESSOR.get();
        if (comp != null) {
            byte[] result = encodeTurbo(image, comp, Math.round(quality * 100));
            if (result != null) return result;
        }
        return encodeImageIO(image, quality);
    }

    private static byte[] encodeTurbo(BufferedImage image, Pointer comp, int quality) {
        int w = image.getWidth();
        int h = image.getHeight();

        int[] px = image.getRGB(0, 0, w, h, null, 0, w);
        byte[] rgb = new byte[px.length * 3];
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            rgb[i * 3] = (byte) ((p >> 16) & 0xFF);
            rgb[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);
            rgb[i * 3 + 2] = (byte) (p & 0xFF);
        }

        var jpegBufRef = new PointerByReference();
        var jpegSizeRef = new NativeLongByReference(new NativeLong(0));

        int rc = TJ.tjCompress2(comp, rgb, w, w * 3, h,
                TJPF_RGB, jpegBufRef, jpegSizeRef, TJSAMP_420, quality, 0);
        if (rc != 0) return null;

        Pointer buf = jpegBufRef.getValue();
        try {
            return buf.getByteArray(0, jpegSizeRef.getValue().intValue());
        } finally {
            TJ.tjFree(buf);
        }
    }

    private static byte[] encodeImageIO(BufferedImage image, float quality) {
        var baos = new ByteArrayOutputStream(4096);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    private static LibTurboJpeg loadTurboJpeg() {
        try {
            return Native.load("turbojpeg", LibTurboJpeg.class);
        } catch (UnsatisfiedLinkError ignored) {
        }

        String[] paths = {
                "/opt/homebrew/opt/jpeg-turbo/lib/libturbojpeg.dylib",
                "/opt/homebrew/lib/libturbojpeg.dylib",
                "/usr/local/opt/jpeg-turbo/lib/libturbojpeg.dylib",
                "/usr/local/lib/libturbojpeg.so",
                "/usr/lib/x86_64-linux-gnu/libturbojpeg.so.0",
                "/usr/lib/aarch64-linux-gnu/libturbojpeg.so.0",
        };
        for (String path : paths) {
            try {
                return Native.load(path, LibTurboJpeg.class);
            } catch (UnsatisfiedLinkError ignored) {
            }
        }
        return null;
    }

    interface LibTurboJpeg extends Library {
        Pointer tjInitCompress();

        int tjCompress2(Pointer handle, byte[] srcBuf, int width, int pitch,
                        int height, int pixelFormat, PointerByReference jpegBuf,
                        NativeLongByReference jpegSize, int jpegSubsamp,
                        int jpegQual, int flags);

        void tjFree(Pointer buffer);

        int tjDestroy(Pointer handle);
    }
}
