package dev.olivelli.fxcenter.desktop;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import java.awt.Color;
import java.awt.Font;
import java.util.HashMap;
import java.util.Map;
import javax.swing.UIManager;

/**
 * The look of the app: dark, because it runs beside a stage in the dark and a
 * bright screen lights up the wings and whoever sits at it.
 *
 * <p>Rules the palette follows: no large bright area anywhere (the window is
 * near black, text is grey rather than white), colour only where it carries
 * meaning (what plays, GO, STOP, the kind of cue), and warm accents (amber)
 * rather than cold blue-white, which dazzles more in the dark.
 */
final class Theme {

    // Surfaces, darkest first.
    static final Color BG = new Color(0x111316);
    static final Color SURFACE = new Color(0x171a1e);
    static final Color RAISED = new Color(0x1f2328);
    static final Color BORDER = new Color(0x2b3037);

    // Text.
    static final Color TEXT = new Color(0xc4c8ce);
    static final Color TEXT_DIM = new Color(0x7c838c);
    static final Color TEXT_FAINT = new Color(0x4f555d);

    // Meaning.
    static final Color ACCENT = new Color(0xe0a33a);        // amber: what is playing
    static final Color ACCENT_BG = new Color(0x2e2414);
    static final Color SELECTION = new Color(0x263447);
    static final Color MUSIC = new Color(0x8fb2e3);
    static final Color EFFECT = new Color(0x77c4a8);
    static final Color OK = new Color(0x6cc28a);
    static final Color ERROR = new Color(0xe07a6e);

    // The transport: filled, but deep, so three big buttons do not glow.
    static final Color GO = new Color(0x235a38);
    static final Color STOP = new Color(0x7a2925);
    static final Color PAUSE = new Color(0x343a42);
    static final Color ON_FILL = new Color(0xe6e9ec);

    // The clip editor's waveform.
    static final Color WAVE_BG = new Color(0x14171b);
    static final Color WAVE_IN = new Color(0x4f76a8);
    static final Color WAVE_OUT = new Color(0x343b45);
    static final Color HANDLE_START = new Color(0x5cbf7d);
    static final Color HANDLE_END = new Color(0xe0685c);

    private Theme() {
    }

    static void install() {
        FlatInterFont.install();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        Map<String, String> c = new HashMap<>();
        c.put("@background", hex(BG));
        c.put("@foreground", hex(TEXT));
        c.put("@accentColor", hex(ACCENT));
        c.put("@selectionBackground", hex(SELECTION));
        c.put("@selectionForeground", hex(TEXT));
        c.put("@disabledForeground", hex(TEXT_FAINT));
        c.put("Component.borderColor", hex(BORDER));
        c.put("Component.focusColor", hex(ACCENT.darker()));
        c.put("Component.focusedBorderColor", hex(ACCENT.darker()));
        c.put("Button.background", hex(RAISED));
        c.put("Button.hoverBackground", "lighten(" + hex(RAISED) + ",4%)");
        c.put("Button.pressedBackground", "lighten(" + hex(RAISED) + ",8%)");
        c.put("Button.borderColor", hex(BORDER));
        c.put("Button.default.background", hex(ACCENT_BG));
        c.put("Button.default.foreground", hex(ACCENT));
        c.put("Button.default.borderColor", hex(ACCENT.darker().darker()));
        c.put("TextField.background", hex(SURFACE));
        c.put("List.background", hex(SURFACE));
        c.put("ScrollPane.background", hex(SURFACE));
        c.put("ProgressBar.background", hex(BG));
        c.put("ProgressBar.foreground", hex(ACCENT));
        c.put("Slider.trackColor", hex(RAISED));
        c.put("Slider.trackValueColor", hex(TEXT_DIM));
        c.put("Slider.thumbColor", hex(TEXT_DIM));
        c.put("ToolTip.background", hex(RAISED));
        c.put("PopupMenu.background", hex(RAISED));
        c.put("TitlePane.background", hex(BG));
        FlatLaf.setGlobalExtraDefaults(c);
        FlatDarkLaf.setup();
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("ProgressBar.arc", 6);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new java.awt.Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.track", SURFACE);
    }

    static Font font(float size, int style) {
        return UIManager.getFont("defaultFont").deriveFont(style, size);
    }

    static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xffffff);
    }
}
