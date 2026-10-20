package com.plupot.build;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off prep tool. Reads the user-supplied master icon at
 * {@code assets/icon.png} and derives every format the build needs.
 *
 * <p>Run once after compiling:</p>
 * <pre>
 *   java -cp out com.plupot.build.IconBuilder
 * </pre>
 *
 * <p>If {@code assets/icon.png} is missing, the tool creates {@code assets/}
 * and prints where to drop the file, then exits without error.</p>
 */
public final class IconBuilder {

    /** Sizes written into {@code src/com/plupot/res/} for the runtime window icon. */
    private static final int[] PNG_SIZES = {16, 24, 32, 48, 64, 128, 256, 512};

    private static final Path ASSETS  = Path.of("assets");
    private static final Path SOURCE  = ASSETS.resolve("icon.png");
    private static final Path RES     = Path.of("src", "com", "plupot", "res");
    private static final Path PKG_DIR = Path.of("icon");

    private IconBuilder() {
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(ASSETS);

        if (!Files.isRegularFile(SOURCE)) {
            System.err.println(
                    "[Plupot] No master icon found.\n"
                            + "         Drop a square PNG (512x512 or 1024x1024 recommended) at:\n"
                            + "             " + SOURCE.toAbsolutePath() + "\n"
                            + "         then run this tool again.");
            return;
        }

        BufferedImage master = ImageIO.read(SOURCE.toFile());
        if (master == null) {
            System.err.println("[Plupot] Could not decode " + SOURCE);
            return;
        }
        build(master);
    }

    // ------------------------------------------------------------------ build

    private static void build(BufferedImage master) throws IOException {
        if (master.getWidth() != master.getHeight()) {
            System.err.println("[Plupot] Warning: icon.png is not square ("
                    + master.getWidth() + "\u00D7" + master.getHeight()
                    + "). Cropping to center square.");
            master = cropSquare(master);
        }

        int srcSize = master.getWidth();
        if (srcSize < 256) {
            System.err.println("[Plupot] Warning: icon.png is only " + srcSize
                    + "px. Large icons will blur; use 512 or 1024 for best results.");
        }

        Files.createDirectories(RES);
        Files.createDirectories(PKG_DIR);

        List<Integer> icoSizes = new ArrayList<>();
        List<byte[]> icoPngs = new ArrayList<>();

        // Runtime PNGs (never upscale past source)
        for (int size : PNG_SIZES) {
            if (size > srcSize) continue;
            BufferedImage img = size == srcSize ? master : downscale(master, size);

            Path file = RES.resolve("icon_" + size + ".png");
            ImageIO.write(img, "png", file.toFile());
            System.out.println("  " + file);

            if (size <= 256) {
                icoSizes.add(size);
                icoPngs.add(toPngBytes(img));
            }
        }

        // Generic "icon.png" copy for Linux / fallback loaders
        Path generic = RES.resolve("icon.png");
        ImageIO.write(master, "png", generic.toFile());
        System.out.println("  " + generic);

        // Windows .ico
        Path icoFile = PKG_DIR.resolve("icon.ico");
        writeIco(icoFile, icoSizes, icoPngs);
        System.out.println("  " + icoFile);

        // macOS .icns
        Path icnsFile = PKG_DIR.resolve("icon.icns");
        writeIcns(icnsFile, master);
        System.out.println("  " + icnsFile);

        System.out.println("\nDone. Master source: " + SOURCE.toAbsolutePath());
    }

    // ------------------------------------------------------------------ scaling

    private static BufferedImage cropSquare(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int s = Math.min(w, h);
        int x = (w - s) / 2;
        int y = (h - s) / 2;
        BufferedImage out = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, s, s, x, y, x + s, y + s, null);
        g.dispose();
        return out;
    }

    private static BufferedImage downscale(BufferedImage src, int target) {
        BufferedImage cur = src;
        int size = src.getWidth();
        while (size > target) {
            int next = Math.max(target, size / 2);
            cur = resize(cur, next);
            size = next;
        }
        return size == target ? cur : resize(cur, target);
    }

    private static BufferedImage resize(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static byte[] toPngBytes(BufferedImage img) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ .ico

    private static void writeIco(Path file, List<Integer> sizes, List<byte[]> pngs)
            throws IOException {
        int count = sizes.size();
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file))) {
            writeLE16(out, 0);          // reserved
            writeLE16(out, 1);          // type: 1 = icon
            writeLE16(out, count);

            int offset = 6 + 16 * count;
            for (int i = 0; i < count; i++) {
                int sz = sizes.get(i);
                byte[] data = pngs.get(i);

                out.writeByte(sz >= 256 ? 0 : sz);   // 0 encodes 256
                out.writeByte(sz >= 256 ? 0 : sz);
                out.writeByte(0);
                out.writeByte(0);
                writeLE16(out, 1);                   // planes
                writeLE16(out, 32);                  // bpp
                writeLE32(out, data.length);
                writeLE32(out, offset);

                offset += data.length;
            }
            for (byte[] data : pngs) {
                out.write(data);
            }
        }
    }

    private static void writeLE16(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
    }

    private static void writeLE32(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
        out.writeByte((v >> 16) & 0xFF);
        out.writeByte((v >> 24) & 0xFF);
    }

    // ------------------------------------------------------------------ .icns

    private static void writeIcns(Path file, BufferedImage master) throws IOException {
        int srcSize = master.getWidth();

        Object[][] entries = {
                {"ic11", 32},   //  16@2x
                {"ic12", 64},   //  32@2x
                {"ic07", 128},  // 128
                {"ic13", 256},  // 128@2x
                {"ic08", 256},  // 256
                {"ic14", 512},  // 256@2x
                {"ic09", 512},  // 512
                {"ic10", 1024}, // 512@2x
        };

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Object[] e : entries) {
            String type = (String) e[0];
            int px = (Integer) e[1];
            if (px > srcSize) continue;

            byte[] png = toPngBytes(px == srcSize ? master : downscale(master, px));

            body.write(type.getBytes(StandardCharsets.US_ASCII));
            writeBE32(body, png.length + 8);
            body.write(png);
        }

        byte[] data = body.toByteArray();
        try (OutputStream os = Files.newOutputStream(file)) {
            os.write("icns".getBytes(StandardCharsets.US_ASCII));
            writeBE32(os, data.length + 8);
            os.write(data);
        }
    }

    private static void writeBE32(OutputStream out, int v) throws IOException {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}