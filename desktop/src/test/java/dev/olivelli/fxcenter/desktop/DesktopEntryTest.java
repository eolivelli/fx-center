package dev.olivelli.fxcenter.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class DesktopEntryTest {

    @Test
    void declaresTheWindowClassAwtUses() {
        String t = DesktopEntry.text(Paths.get("/opt/java/bin/java"), Paths.get("/home/u/My Apps/fx-center.jar"),
                Paths.get("/home/u/.local/share/icons/fx-center.png"));
        assertTrue(t.contains("\nStartupWMClass=dev-olivelli-fxcenter-desktop-FxCenterApp\n"), t);
        assertTrue(t.contains("\nExec=\"/opt/java/bin/java\" -jar \"/home/u/My Apps/fx-center.jar\"\n"), t);
        assertTrue(t.contains("\nIcon=/home/u/.local/share/icons/fx-center.png\n"), t);
    }

    @Test
    void quotingEscapesWhatTheSpecSays() {
        assertEquals("\"a\\$b\\\"c\\\\d\"", DesktopEntry.quote("a$b\"c\\d"));
    }

    @Test
    void iconIsDrawnAtEverySize() {
        for (int s : AppIcon.SIZES) {
            BufferedImage img = AppIcon.image(s);
            assertEquals(s, img.getWidth());
            // amber in the middle, transparent in the corner
            assertEquals(0, img.getRGB(0, 0) >>> 24);
            assertTrue((img.getRGB(s / 2, s / 2) & 0xffffff) == (Theme.ACCENT.getRGB() & 0xffffff));
        }
    }
}
