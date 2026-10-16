package com.storagemanager.storage_management;

import com.storagemanager.storage_management.service.pdf.PngImages;
import org.junit.jupiter.api.Test;
import org.openpdf.text.Image;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El lector de PNG propio, que sustituye a ImageIO en la imagen nativa: lo que
 * lee tiene que ser lo mismo que lee ImageIO, píxel a píxel, en los tipos de
 * PNG que salen de un navegador o de un programa de dibujo. Aquí, en la JVM,
 * ImageIO sí funciona y sirve de referencia.
 */
class PngImagesTest {

    @Test
    void readsTheSameAsImageIoForEveryKindOfPng() throws Exception {
        // El gris no entra en la comparación: al leerlo, ImageIO lo pasa por su
        // espacio de color y getRGB no devuelve el valor guardado tal cual.
        for (int type : new int[]{BufferedImage.TYPE_INT_ARGB, BufferedImage.TYPE_INT_RGB,
                BufferedImage.TYPE_4BYTE_ABGR}) {
            check(drawing(type), "tipo " + type);
        }
        check(paletted(), "paleta");
    }

    @Test
    void aSignatureKeepsItsTransparencyAsAMask() throws Exception {
        Image image = PngImages.toImage(png(drawing(BufferedImage.TYPE_INT_ARGB)));
        assertEquals(120f, image.getWidth());
        assertEquals(60f, image.getHeight());
        assertNotNull(image.getImageMask(), "lo que no es trazo tiene que quedar transparente");
    }

    @Test
    void somethingThatIsNotAPngSaysSo() {
        assertFalse(PngImages.isPng(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9}));
        assertThrows(IllegalArgumentException.class, () -> PngImages.toImage(new byte[]{1, 2, 3}));
    }

    // ------------------------------------------------------------------

    private static void check(BufferedImage source, String what) throws Exception {
        byte[] png = png(source);
        BufferedImage expected = ImageIO.read(new java.io.ByteArrayInputStream(png));
        PngImages.Decoded decoded = PngImages.decode(png);
        int width = decoded.width();
        int height = decoded.height();
        byte[] rgb = decoded.rgb();
        byte[] alpha = decoded.alpha();
        assertEquals(expected.getWidth(), width, what);
        assertEquals(expected.getHeight(), height, what);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = expected.getRGB(x, y);
                int i = y * width + x;
                int a = alpha == null ? 255 : alpha[i] & 0xFF;
                assertEquals((argb >>> 24) & 0xFF, a, what + " alfa en " + x + "," + y);
                if (a == 0) continue; // el color de lo transparente no importa
                assertEquals((argb >> 16) & 0xFF, rgb[i * 3] & 0xFF, what + " rojo en " + x + "," + y);
                assertEquals((argb >> 8) & 0xFF, rgb[i * 3 + 1] & 0xFF, what + " verde en " + x + "," + y);
                assertEquals(argb & 0xFF, rgb[i * 3 + 2] & 0xFF, what + " azul en " + x + "," + y);
            }
        }
    }

    private static BufferedImage drawing(int type) {
        BufferedImage image = new BufferedImage(120, 60, type);
        Graphics2D g = image.createGraphics();
        if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_BYTE_GRAY) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 120, 60);
        }
        g.setColor(new Color(0x1e, 0x3a, 0x8a, 200));
        g.setStroke(new BasicStroke(3));
        g.drawLine(5, 50, 60, 8);
        g.drawLine(60, 8, 115, 45);
        g.setColor(new Color(200, 30, 40));
        g.fillOval(50, 25, 20, 20);
        g.dispose();
        return image;
    }

    private static BufferedImage paletted() {
        byte[] r = {(byte) 255, 0, (byte) 200, 30};
        byte[] g = {(byte) 255, 0, 30, (byte) 160};
        byte[] b = {(byte) 255, 0, 40, 90};
        BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_BYTE_INDEXED, new IndexColorModel(2, 4, r, g, b));
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 40; x++) {
                image.getRaster().setSample(x, y, 0, (x + y) % 4);
            }
        }
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }
}
