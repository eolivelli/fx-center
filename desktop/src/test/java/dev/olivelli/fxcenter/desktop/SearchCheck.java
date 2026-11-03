package dev.olivelli.fxcenter.desktop;

import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * Manual check, not a test: the search box against a real show folder. Types
 * a query, paints the filtered window, checks that space and P type into the
 * box instead of GO and PAUSA, then plays a found song and checks the filter
 * is gone with that song still selected.
 *
 * <pre>java -cp desktop/target/test-classes:desktop/target/fx-center-*.jar \
 *     dev.olivelli.fxcenter.desktop.SearchCheck HOME_DIR out.png query</pre>
 */
public final class SearchCheck {

    private SearchCheck() {
    }

    public static void main(String[] args) throws Exception {
        Theme.install();
        MainWindow[] w = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new MainWindow(Paths.get(args[0]));
            w[0].setVisible(true);
        });
        Thread.sleep(4000);
        JTextField search = field(w[0], "search");
        @SuppressWarnings("unchecked")
        JList<?> list = (JList<?>) field(w[0], "list");
        int all = list.getModel().getSize();
        SwingUtilities.invokeAndWait(() -> {
            search.requestFocusInWindow();
            search.setText(args[2]);
        });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> paint(w[0], args[1]));
        int found = list.getModel().getSize();
        System.out.println("rows: all " + all + ", filtered '" + args[2] + "' " + found);

        // space and P while typing: letters, not GO / PAUSA
        SwingUtilities.invokeAndWait(() -> {
            search.setCaretPosition(search.getText().length());
            type(search, KeyEvent.VK_SPACE, ' ');
            type(search, KeyEvent.VK_P, 'p');
        });
        Thread.sleep(500);
        System.out.println("after space+p: text='" + search.getText() + "' state=" + w[0].controllerForTests().state());
        SwingUtilities.invokeAndWait(() -> search.setText(args[2]));

        // pick the last found row, then GO with space on the list
        int[] picked = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            list.setSelectedIndex(list.getModel().getSize() - 1);
            picked[0] = w[0].controllerForTests().selectedIndex();
            list.requestFocusInWindow();
            type(list, KeyEvent.VK_SPACE, ' ');
        });
        Thread.sleep(1500);
        int sel = list.getSelectedIndex();
        System.out.println("after GO: text='" + search.getText() + "' rows=" + list.getModel().getSize()
                + " picked=" + picked[0] + " selectedRow=" + sel + " playing=" + w[0].controllerForTests().playingIndex()
                + " state=" + w[0].controllerForTests().state());
        SwingUtilities.invokeAndWait(() -> paint(w[0], args[1].replace(".png", "-after-go.png")));
        w[0].controllerForTests().stop();
        Thread.sleep(300);
        System.exit(0);
    }

    private static void type(Component c, int code, char ch) {
        long t = System.currentTimeMillis();
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, t, 0, code, ch));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_TYPED, t, 0, KeyEvent.VK_UNDEFINED, ch));
        c.dispatchEvent(new KeyEvent(c, KeyEvent.KEY_RELEASED, t, 0, code, ch));
    }

    private static <T> T field(Object o, String name) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        T t = (T) f.get(o);
        return t;
    }

    private static void paint(Component c, String out) {
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        c.paint(img.getGraphics());
        try {
            ImageIO.write(img, "png", new File(out));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
