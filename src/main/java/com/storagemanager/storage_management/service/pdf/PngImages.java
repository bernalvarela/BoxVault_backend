package com.storagemanager.storage_management.service.pdf;

import org.openpdf.text.Image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Lee un PNG y lo convierte en una imagen de OpenPDF sin pasar por
 * {@code javax.imageio}.
 * <p>
 * OpenPDF abre los PNG con ImageIO, que tira de las bibliotecas gráficas de
 * escritorio de Java (AWT). En la imagen nativa estática (musl) no existen, y
 * el primer PNG revienta con un "Could not initialize class
 * javax.imageio.ImageIO": justo al estampar las firmas en el contrato. Un PNG
 * no es más que filas de píxeles comprimidas con zlib, y zlib sí está en el
 * JDK (Inflater), así que se descomprime aquí y a OpenPDF se le dan los
 * píxeles en crudo y, aparte, la transparencia como máscara suave.
 * <p>
 * Admite lo que sale de un navegador o de un programa de dibujo: gris, color,
 * paleta, con o sin transparencia, de 1 a 16 bits. No admite PNG entrelazados
 * (raros); para esos dice qué pasa en vez de pintar basura.
 */
public final class PngImages {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngImages() {}

    /** Si esos bytes son un PNG. */
    public static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < SIGNATURE.length) return false;
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (bytes[i] != SIGNATURE[i]) return false;
        }
        return true;
    }

    /** La imagen para el PDF, con su transparencia si la tiene. */
    public static Image toImage(byte[] png) {
        Decoded decoded = decode(png);
        try {
            Image image = Image.getInstance(decoded.width, decoded.height, 3, 8, decoded.rgb);
            if (decoded.alpha != null) {
                Image mask = Image.getInstance(decoded.width, decoded.height, 1, 8, decoded.alpha);
                mask.makeMask();
                image.setImageMask(mask);
            }
            return image;
        } catch (Exception e) {
            throw new IllegalArgumentException("No se pudo convertir el PNG para el PDF", e);
        }
    }

    /** Los píxeles en RGB (3 bytes cada uno) y la transparencia aparte (1 byte), o null si no tiene. */
    public record Decoded(int width, int height, byte[] rgb, byte[] alpha) {}

    public static Decoded decode(byte[] png) {
        if (!isPng(png)) throw new IllegalArgumentException("No es un PNG");

        int width = 0, height = 0, bitDepth = 0, colorType = -1, interlace = 0;
        byte[] palette = null;
        byte[] transparency = null;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        int at = SIGNATURE.length;
        while (at + 8 <= png.length) {
            int length = readInt(png, at);
            String type = new String(png, at + 4, 4, StandardCharsets.US_ASCII);
            int data = at + 8;
            if (length < 0 || data + length > png.length) throw new IllegalArgumentException("PNG cortado");
            switch (type) {
                case "IHDR" -> {
                    width = readInt(png, data);
                    height = readInt(png, data + 4);
                    bitDepth = png[data + 8] & 0xFF;
                    colorType = png[data + 9] & 0xFF;
                    interlace = png[data + 12] & 0xFF;
                }
                case "PLTE" -> palette = java.util.Arrays.copyOfRange(png, data, data + length);
                case "tRNS" -> transparency = java.util.Arrays.copyOfRange(png, data, data + length);
                case "IDAT" -> compressed.write(png, data, length);
                default -> { }
            }
            if (type.equals("IEND")) break;
            at = data + length + 4; // + CRC
        }

        if (width <= 0 || height <= 0) throw new IllegalArgumentException("PNG sin cabecera");
        if (interlace != 0) throw new IllegalArgumentException("PNG entrelazado: no se admite");
        int channels = switch (colorType) {
            case 0 -> 1;      // gris
            case 2 -> 3;      // color
            case 3 -> 1;      // paleta
            case 4 -> 2;      // gris con transparencia
            case 6 -> 4;      // color con transparencia
            default -> throw new IllegalArgumentException("Tipo de PNG desconocido: " + colorType);
        };
        if (colorType == 3 && palette == null) throw new IllegalArgumentException("PNG de paleta sin paleta");

        int bitsPerPixel = channels * bitDepth;
        int bytesPerPixel = Math.max(1, (bitsPerPixel + 7) / 8);
        int stride = (width * bitsPerPixel + 7) / 8;
        byte[] raw = inflate(compressed.toByteArray(), (stride + 1) * height);
        byte[] rows = unfilter(raw, height, stride, bytesPerPixel);

        boolean hasAlpha = colorType == 4 || colorType == 6 || transparency != null;
        byte[] rgb = new byte[width * height * 3];
        byte[] alpha = hasAlpha ? new byte[width * height] : null;

        for (int y = 0; y < height; y++) {
            int row = y * stride;
            for (int x = 0; x < width; x++) {
                int pixel = y * width + x;
                int r, g, b, a = 255;
                switch (colorType) {
                    case 0 -> {
                        int v = sample(rows, row, x, 0, 1, bitDepth);
                        r = g = b = scale(v, bitDepth);
                        if (transparency != null && transparency.length >= 2 && v == readShort(transparency, 0)) a = 0;
                    }
                    case 2 -> {
                        int sr = sample(rows, row, x, 0, 3, bitDepth);
                        int sg = sample(rows, row, x, 1, 3, bitDepth);
                        int sb = sample(rows, row, x, 2, 3, bitDepth);
                        r = scale(sr, bitDepth);
                        g = scale(sg, bitDepth);
                        b = scale(sb, bitDepth);
                        if (transparency != null && transparency.length >= 6 && sr == readShort(transparency, 0)
                                && sg == readShort(transparency, 2) && sb == readShort(transparency, 4)) {
                            a = 0;
                        }
                    }
                    case 3 -> {
                        int index = sample(rows, row, x, 0, 1, bitDepth);
                        if (index * 3 + 2 >= palette.length) throw new IllegalArgumentException("PNG con un color fuera de la paleta");
                        r = palette[index * 3] & 0xFF;
                        g = palette[index * 3 + 1] & 0xFF;
                        b = palette[index * 3 + 2] & 0xFF;
                        if (transparency != null && index < transparency.length) a = transparency[index] & 0xFF;
                    }
                    case 4 -> {
                        r = g = b = scale(sample(rows, row, x, 0, 2, bitDepth), bitDepth);
                        a = scale(sample(rows, row, x, 1, 2, bitDepth), bitDepth);
                    }
                    default -> {
                        r = scale(sample(rows, row, x, 0, 4, bitDepth), bitDepth);
                        g = scale(sample(rows, row, x, 1, 4, bitDepth), bitDepth);
                        b = scale(sample(rows, row, x, 2, 4, bitDepth), bitDepth);
                        a = scale(sample(rows, row, x, 3, 4, bitDepth), bitDepth);
                    }
                }
                rgb[pixel * 3] = (byte) r;
                rgb[pixel * 3 + 1] = (byte) g;
                rgb[pixel * 3 + 2] = (byte) b;
                if (alpha != null) alpha[pixel] = (byte) a;
            }
        }
        return new Decoded(width, height, rgb, alpha);
    }

    /** El valor de un canal de un píxel, tal cual viene (de 1 a 16 bits). */
    private static int sample(byte[] rows, int row, int x, int channel, int channels, int bitDepth) {
        if (bitDepth == 8) return rows[row + x * channels + channel] & 0xFF;
        if (bitDepth == 16) {
            int i = row + (x * channels + channel) * 2;
            return ((rows[i] & 0xFF) << 8) | (rows[i + 1] & 0xFF);
        }
        // 1, 2 o 4 bits: varios píxeles por byte, el primero en los bits altos.
        int bit = (x * channels + channel) * bitDepth;
        int value = rows[row + bit / 8] & 0xFF;
        int shift = 8 - bitDepth - (bit % 8);
        return (value >> shift) & ((1 << bitDepth) - 1);
    }

    /** Un valor de esa profundidad llevado a 0-255. */
    private static int scale(int value, int bitDepth) {
        return switch (bitDepth) {
            case 8 -> value;
            case 16 -> value >> 8;
            default -> value * 255 / ((1 << bitDepth) - 1);
        };
    }

    private static byte[] inflate(byte[] compressed, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expected];
            int total = 0;
            while (total < expected && !inflater.finished()) {
                int read = inflater.inflate(out, total, expected - total);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                total += read;
            }
            if (total < expected) throw new IllegalArgumentException("PNG incompleto");
            return out;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("PNG dañado", e);
        } finally {
            inflater.end();
        }
    }

    /** Deshace los filtros de cada fila (ninguno, resta, arriba, media y Paeth). */
    private static byte[] unfilter(byte[] raw, int height, int stride, int bpp) {
        byte[] rows = new byte[height * stride];
        for (int y = 0; y < height; y++) {
            int filter = raw[y * (stride + 1)] & 0xFF;
            int src = y * (stride + 1) + 1;
            int dst = y * stride;
            int prev = dst - stride;
            for (int i = 0; i < stride; i++) {
                int x = raw[src + i] & 0xFF;
                int left = i >= bpp ? rows[dst + i - bpp] & 0xFF : 0;
                int up = y > 0 ? rows[prev + i] & 0xFF : 0;
                int upLeft = y > 0 && i >= bpp ? rows[prev + i - bpp] & 0xFF : 0;
                int value = switch (filter) {
                    case 0 -> x;
                    case 1 -> x + left;
                    case 2 -> x + up;
                    case 3 -> x + ((left + up) >> 1);
                    case 4 -> x + paeth(left, up, upLeft);
                    default -> throw new IllegalArgumentException("PNG con un filtro desconocido: " + filter);
                };
                rows[dst + i] = (byte) value;
            }
        }
        return rows;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static int readShort(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }
}
