package dev.olivelli.fxcenter.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The app's icon, drawn rather than shipped: the amber play triangle on the
 * dark background, as on Android. Sharp at every size the desktop asks for.
 */
final class AppIcon {

    static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private AppIcon() {
    }

    static List<Image> images() {
        List<Image> out = new ArrayList<>();
        for (int s : SIZES) {
            out.add(image(s));
        }
        return out;
    }

    static BufferedImage image(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double s = size;
        double inset = s * 0.04;
        g.setColor(Theme.BG);
        g.fill(new RoundRectangle2D.Double(inset, inset, s - 2 * inset, s - 2 * inset, s * 0.36, s * 0.36));
        g.setColor(new Color(0x2b3037));
        g.draw(new RoundRectangle2D.Double(inset, inset, s - 2 * inset - 1, s - 2 * inset - 1, s * 0.36, s * 0.36));
        // the triangle of the Android icon (40,30 80,54 40,78 on 108), centred by its weight
        Path2D t = new Path2D.Double();
        double k = s / 108.0;
        double dx = s * 0.03;
        t.moveTo(40 * k + dx, 30 * k);
        t.lineTo(80 * k + dx, 54 * k);
        t.lineTo(40 * k + dx, 78 * k);
        t.closePath();
        g.setColor(Theme.ACCENT);
        g.fill(t);
        g.dispose();
        return img;
    }
}
