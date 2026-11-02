package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.AudioPlayer;
import dev.olivelli.fxcenter.core.Clip;
import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.Scaletta;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;

/**
 * Chooses the part of a song a cue plays, by ear. The loop it is built for:
 * click on the waveform to listen from there, press {@code [} or {@code ]} at
 * the right moment, fine-tune with the arrows, then "Ascolta il finale" to
 * hear the last seconds and the fade exactly as the audience will.
 *
 * <p>Modal; returns the new clip, or null when cancelled. It plays through its
 * own player, so the show's player is not touched.
 */
final class ClipEditor extends JDialog {

    private static final Color OUTSIDE = new Color(0x0d0f12);
    private static final Color WAVE_IN = Theme.WAVE_IN;
    private static final Color WAVE_OUT = Theme.WAVE_OUT;
    private static final Color HANDLE = Theme.HANDLE_START;
    private static final Color HANDLE_END = Theme.HANDLE_END;
    private static final Color PLAYHEAD = Theme.ACCENT;
    private static final long NUDGE = 100;
    private static final long NUDGE_BIG = 1000;
    private static final long FINALE = 5000;

    private enum Handle { START, END }

    private static final String HINT = "<html>Clic sull'onda: ascolta da lì. Trascina la maniglia "
            + "<font color=#5cbf7d><b>verde</b></font> (inizio) e quella <font color=#e0685c><b>rossa</b></font> (fine)."
            + "<br><b>[</b> e <b>]</b>: inizio e fine dove sta suonando adesso &nbsp;·&nbsp; <b>←/→</b> ±0,1 s, "
            + "<b>Maiusc</b> ±1 s &nbsp;·&nbsp; <b>Spazio</b> ascolta/ferma</html>";

    private final Path file;
    private final AudioPlayer preview = new JavaSoundPlayer();
    private final WavePanel wave = new WavePanel();
    private final JTextField startField = new JTextField(7);
    private final JTextField endField = new JTextField(7);
    private final JLabel lengthLabel = new JLabel(" ");
    private final JLabel hint = new JLabel(" ");
    private final JButton playButton = new JButton("▶  Ascolta il taglio");
    private final Timer ticker;

    private Waveform waveform;
    private long duration = -1;
    private long start;
    private long end; // -1 until the duration is known, when there is no end point
    private Handle active = Handle.START;
    private long previewFrom = -1; // where the preview being played started, in file time
    private long playhead = 0;
    private Clip result;

    ClipEditor(Window owner, Cue cue, Path file) {
        super(owner, "Taglia: " + cue.title(), ModalityType.APPLICATION_MODAL);
        this.file = file;
        this.start = cue.clip().startMillis();
        this.end = cue.clip().endMillis();
        this.playhead = start;
        preview.setListener(new AudioPlayer.Listener() {
            @Override
            public void finished() {
                SwingUtilities.invokeLater(() -> stopPreview());
            }

            @Override
            public void failed(String message) {
                SwingUtilities.invokeLater(() -> {
                    stopPreview();
                    hint.setText(message);
                });
            }
        });
        buildUi();
        ticker = new Timer(40, e -> tick());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                ticker.stop();
                preview.close();
            }
        });
        setSize(Math.min(1200, Math.max(900, owner.getWidth())), 400);
        setMinimumSize(new Dimension(700, 340));
        setLocationRelativeTo(owner);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                wave.requestFocusInWindow();
            }
        });
        loadWaveform();
    }

    /** Shows the editor; the clip chosen, or null if the user cancelled. */
    static Clip edit(Window owner, Cue cue, Path file) {
        ClipEditor d = new ClipEditor(owner, cue, file);
        d.setVisible(true);
        return d.result;
    }

    // --- layout ------------------------------------------------------------

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setContentPane(root);

        hint.setFont(Theme.font(12f, Font.PLAIN));
        hint.setForeground(Theme.TEXT_DIM);
        hint.setText(HINT);
        root.add(hint, BorderLayout.NORTH);

        root.add(wave, BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));

        JPanel times = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton setStart = new JButton("[  Inizio qui");
        setStart.setToolTipText("Mette l'inizio dove sta suonando adesso (tasto [ )");
        setStart.addActionListener(e -> setAtPlayhead(Handle.START));
        JButton setEnd = new JButton("Fine qui  ]");
        setEnd.setToolTipText("Mette la fine dove sta suonando adesso (tasto ] )");
        setEnd.addActionListener(e -> setAtPlayhead(Handle.END));
        times.add(new JLabel("Inizio"));
        times.add(startField);
        times.add(setStart);
        times.add(Box.createHorizontalStrut(16));
        times.add(new JLabel("Fine"));
        times.add(endField);
        times.add(setEnd);
        times.add(Box.createHorizontalStrut(16));
        lengthLabel.setFont(Theme.font(14f, Font.BOLD));
        lengthLabel.setForeground(Theme.ACCENT);
        times.add(lengthLabel);
        startField.addActionListener(e -> {
            typed(startField, Handle.START);
            wave.requestFocusInWindow();
        });
        endField.addActionListener(e -> {
            typed(endField, Handle.END);
            wave.requestFocusInWindow();
        });
        startField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                typed(startField, Handle.START);
            }
        });
        endField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                typed(endField, Handle.END);
            }
        });
        south.add(times);
        south.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel(new BorderLayout());
        JPanel listen = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        playButton.setToolTipText("Dall'inizio del taglio, fino alla fine con la sfumatura (Spazio)");
        playButton.addActionListener(e -> togglePlay());
        JButton finale = new JButton("▶  Ascolta il finale");
        finale.setToolTipText("Gli ultimi 5 secondi prima della fine, con la sfumatura, come li sentirà il pubblico");
        finale.addActionListener(e -> playFinale());
        listen.add(playButton);
        listen.add(finale);
        buttons.add(listen, BorderLayout.WEST);

        JPanel close = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton remove = new JButton("Brano intero");
        remove.setToolTipText("Toglie il taglio: il brano suona tutto");
        remove.addActionListener(e -> {
            result = Clip.NONE;
            dispose();
        });
        JButton cancel = new JButton("Annulla");
        cancel.addActionListener(e -> dispose());
        JButton ok = new JButton("OK");
        ok.setFont(Theme.font(13f, Font.BOLD));
        ok.addActionListener(e -> accept());
        close.add(remove);
        close.add(Box.createHorizontalStrut(12));
        close.add(cancel);
        close.add(ok);
        buttons.add(close, BorderLayout.EAST);
        south.add(buttons);
        root.add(south, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);

        JRootPane rp = getRootPane();
        key(rp, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "play", this::togglePlay);
        key(rp, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel", this::dispose);
        key(rp, KeyStroke.getKeyStroke('['), "start", () -> setAtPlayhead(Handle.START));
        key(rp, KeyStroke.getKeyStroke(']'), "end", () -> setAtPlayhead(Handle.END));
        key(rp, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "left", () -> nudge(-NUDGE));
        key(rp, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "right", () -> nudge(NUDGE));
        key(rp, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK), "left1", () -> nudge(-NUDGE_BIG));
        key(rp, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK), "right1", () -> nudge(NUDGE_BIG));
        refreshFields();
    }

    /** A key for the whole dialog, except while typing in a time field. */
    private void key(JComponent c, KeyStroke k, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(k, name);
        c.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (getFocusOwner() instanceof JTextField && !name.equals("cancel")) {
                    return;
                }
                r.run();
            }
        });
    }

    private void loadWaveform() {
        new SwingWorker<Waveform, Void>() {
            @Override
            protected Waveform doInBackground() throws IOException {
                return Waveform.load(file);
            }

            @Override
            protected void done() {
                try {
                    waveform = get();
                    duration = waveform.durationMillis;
                    if (end < 0 || end > duration) {
                        end = duration;
                    }
                    start = Math.min(start, Math.max(0, end - NUDGE));
                    refreshFields();
                    wave.repaint();
                } catch (Exception e) {
                    hint.setText("Non riesco a leggere il file: " + e.getMessage());
                }
            }
        }.execute();
    }

    // --- editing -----------------------------------------------------------

    private boolean ready() {
        return duration > 0;
    }

    private void set(Handle h, long millis) {
        if (!ready()) {
            return;
        }
        long t = Math.round(millis / 100.0) * 100; // the file keeps tenths: edit in tenths
        if (h == Handle.START) {
            start = clamp(t, 0, end - NUDGE);
        } else {
            end = clamp(t, start + NUDGE, duration);
        }
        active = h;
        refreshFields();
        wave.repaint();
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void nudge(long delta) {
        set(active, (active == Handle.START ? start : end) + delta);
        // Hear the result straight away: from the new start, or the last seconds up to the new end.
        if (active == Handle.START) {
            play(start, end);
        } else {
            playFinale();
        }
    }

    private void setAtPlayhead(Handle h) {
        set(h, playhead);
    }

    private void typed(JTextField field, Handle h) {
        if (!ready()) {
            return;
        }
        String text = field.getText().trim();
        try {
            long t = text.isEmpty() ? (h == Handle.START ? 0 : duration) : parse(text);
            set(h, t);
            hint.setForeground(Theme.TEXT_DIM);
        } catch (IllegalArgumentException e) {
            hint.setForeground(Theme.ERROR);
            hint.setText("Tempo non valido: \"" + text + "\" (scrivi per esempio 1:23.4)");
            refreshFields();
        }
    }

    private static long parse(String text) {
        return Scaletta.parseTime(text);
    }

    private void refreshFields() {
        if (!ready()) {
            startField.setText(Scaletta.formatTime(start));
            endField.setText(end < 0 ? "" : Scaletta.formatTime(end));
            lengthLabel.setText("Carico l'onda…");
            return;
        }
        startField.setText(Scaletta.formatTime(start));
        endField.setText(Scaletta.formatTime(end));
        lengthLabel.setText("Durata " + Scaletta.formatTime(end - start) + "   (brano intero " + MainWindow.clock(duration) + ")");
    }

    private void accept() {
        if (!ready()) {
            dispose();
            return;
        }
        boolean toTheEnd = end >= duration - 50;
        result = Clip.of(start, toTheEnd ? -1 : end);
        dispose();
    }

    // --- listening ---------------------------------------------------------

    private void togglePlay() {
        if (previewFrom >= 0) {
            stopPreview();
        } else {
            play(start, end);
        }
    }

    private void playFinale() {
        play(Math.max(start, end - FINALE), end);
    }

    /** Plays from {@code from} up to {@code to} (-1 or past the end: the end of the file), as the show would. */
    private void play(long from, long to) {
        if (!ready()) {
            return;
        }
        try {
            boolean toTheEnd = to < 0 || to >= duration - 50 || to <= from;
            preview.play(file, Clip.of(from, toTheEnd ? -1 : to));
            previewFrom = from;
            playhead = from;
            playButton.setText("■  Ferma");
            ticker.start();
        } catch (IOException | RuntimeException e) {
            hint.setText("Non riesco a riprodurre: " + e.getMessage());
        }
    }

    private void stopPreview() {
        preview.stop();
        previewFrom = -1;
        ticker.stop();
        playButton.setText("▶  Ascolta il taglio");
        wave.repaint();
    }

    private void tick() {
        if (previewFrom >= 0) {
            playhead = previewFrom + preview.positionMillis();
            wave.repaint();
        }
    }

    // --- the waveform ------------------------------------------------------

    /** The song across the whole width, the clip in colour, two handles, and where it is playing. */
    private final class WavePanel extends JComponent {
        private static final int RULER = 18;
        private static final int GRAB = 7;
        private Handle dragging;

        WavePanel() {
            setPreferredSize(new Dimension(800, 180));
            setFocusable(true);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    if (!ready()) {
                        return;
                    }
                    dragging = handleAt(e.getX());
                    if (dragging != null) {
                        active = dragging;
                        repaint();
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging != null) {
                        set(dragging, timeAt(e.getX()));
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!ready()) {
                        return;
                    }
                    if (dragging == Handle.START) {
                        play(start, end);
                    } else if (dragging == Handle.END) {
                        playFinale();
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        long t = timeAt(e.getX());
                        play(t, t < end ? end : -1);
                    }
                    dragging = null;
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(ready() && handleAt(e.getX()) != null
                            ? Cursor.E_RESIZE_CURSOR : Cursor.HAND_CURSOR));
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private int xOf(long millis) {
            return (int) Math.round((double) millis / duration * (getWidth() - 1));
        }

        private long timeAt(int x) {
            return clamp(Math.round((double) x / (getWidth() - 1) * duration), 0, duration);
        }

        private Handle handleAt(int x) {
            int ds = Math.abs(x - xOf(start));
            int de = Math.abs(x - xOf(end));
            if (Math.min(ds, de) > GRAB) {
                return null;
            }
            return ds <= de ? Handle.START : Handle.END;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight() - RULER;
            g.setColor(Theme.WAVE_BG);
            g.fillRect(0, 0, w, getHeight());
            if (!ready()) {
                g.setColor(Theme.TEXT_DIM);
                g.drawString("Carico l'onda del brano…", 12, h / 2);
                g.dispose();
                return;
            }
            int xs = xOf(start);
            int xe = xOf(end);
            g.setColor(OUTSIDE);
            g.fillRect(0, 0, xs, h);
            g.fillRect(xe, 0, w - xe, h);

            int mid = h / 2;
            for (int x = 0; x < w; x++) {
                long t0 = Math.round((double) x / w * duration);
                long t1 = Math.round((double) (x + 1) / w * duration);
                float p = waveform.peak(t0, t1);
                int half = Math.max(1, Math.round(p * (h / 2 - 4)));
                boolean inside = x >= xs && x <= xe;
                g.setColor(inside ? WAVE_IN : WAVE_OUT);
                g.drawLine(x, mid - half, x, mid + half);
            }
            // the end fade, so it is clear where the music starts to drop
            if (end < duration) {
                int xf = xOf(Math.max(start, end - dev.olivelli.fxcenter.core.Clip.END_FADE_MILLIS));
                g.setColor(new Color(224, 104, 92, 45));
                g.fill(new Polygon(new int[]{xf, xe, xe}, new int[]{0, 0, h}, 3));
            }

            drawHandle(g, xs, h, HANDLE, active == Handle.START, "inizio " + Scaletta.formatTime(start), true);
            drawHandle(g, xe, h, HANDLE_END, active == Handle.END, "fine " + Scaletta.formatTime(end), false);

            if (previewFrom >= 0) {
                int xp = xOf(playhead);
                g.setColor(PLAYHEAD);
                g.setStroke(new BasicStroke(2f));
                g.drawLine(xp, 0, xp, h);
            }

            // ruler: a tick every 10 s, a label every 30 s (every 10 s for short files)
            g.setColor(Theme.TEXT_FAINT);
            g.setStroke(new BasicStroke(1f));
            g.drawLine(0, h, w, h);
            g.setFont(getFont().deriveFont(10f));
            long labelEvery = duration > 120_000 ? 30_000 : 10_000;
            for (long t = 0; t <= duration; t += 10_000) {
                int x = xOf(t);
                g.drawLine(x, h, x, h + (t % labelEvery == 0 ? 6 : 3));
                if (t % labelEvery == 0 && x < w - 30) {
                    g.drawString(MainWindow.clock(t), x + 2, h + 15);
                }
            }
            g.dispose();
        }

        private void drawHandle(Graphics2D g, int x, int h, Color c, boolean isActive, String label, boolean leftSide) {
            g.setColor(c);
            g.setStroke(new BasicStroke(isActive ? 3f : 2f));
            g.drawLine(x, 0, x, h);
            int s = isActive ? 9 : 7;
            g.fill(new Polygon(new int[]{x - s, x + s, x}, new int[]{0, 0, s + 2}, 3));
            g.fill(new Polygon(new int[]{x - s, x + s, x}, new int[]{h, h, h - s - 2}, 3));
            g.setFont(getFont().deriveFont(isActive ? Font.BOLD : Font.PLAIN, 12f));
            int tw = g.getFontMetrics().stringWidth(label);
            int lx = leftSide ? x + 8 : x - 8 - tw;
            lx = Math.max(2, Math.min(getWidth() - tw - 2, lx));
            g.setColor(new Color(17, 19, 22, 230));
            g.fillRoundRect(lx - 4, 12, tw + 8, 19, 6, 6);
            g.setColor(c);
            g.drawString(label, lx, 26);
        }
    }
}
