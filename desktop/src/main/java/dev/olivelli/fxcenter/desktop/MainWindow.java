package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.Defaults;
import dev.olivelli.fxcenter.core.Document;
import dev.olivelli.fxcenter.core.DriveClient;
import dev.olivelli.fxcenter.core.DriveFolderSource;
import dev.olivelli.fxcenter.core.DriveLinks;
import dev.olivelli.fxcenter.core.HttpTransport;
import dev.olivelli.fxcenter.core.Library;
import dev.olivelli.fxcenter.core.LibrarySource;
import dev.olivelli.fxcenter.core.LocalFolderSource;
import dev.olivelli.fxcenter.core.Settings;
import dev.olivelli.fxcenter.core.ShowController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The one window: the cue list, and a transport made of a few very large
 * buttons. Everything that may block (listing, downloading, opening a file)
 * runs on {@link #worker}, one task at a time; the controller reports back
 * through {@link #changed()} and {@link #error(String)} on the EDT.
 */
final class MainWindow extends JFrame implements ShowController.Listener {

    private static final Color GREEN = new Color(0x2e7d32);
    private static final Color RED = new Color(0xc62828);
    private static final Color MUSIC = new Color(0x1256cc);
    private static final Color EFFECT = new Color(0x0a7a5c);
    private static final Color PLAYING_BG = new Color(0xfff3c4);

    private final Path homeDir;
    private final Settings settings;
    private final ShowController controller;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fx-center-worker");
        t.setDaemon(true);
        return t;
    });

    private final DefaultListModel<Cue> model = new DefaultListModel<>();
    private final JList<Cue> list = new JList<>(model);
    private final JLabel sourceLabel = new JLabel();
    private final JLabel nowPlaying = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JLabel time = new JLabel("0:00 / 0:00");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton goButton = bigButton("▶  VAI", GREEN);
    private final JButton stopButton = bigButton("■  STOP", RED);
    private final JButton pauseButton = bigButton("❚❚  PAUSA", new Color(0x555555));
    private final JButton docsButton = new JButton("Documenti ▾");
    private boolean syncingSelection;

    MainWindow(Path homeDir) {
        super("FX Center");
        this.homeDir = homeDir;
        this.settings = Settings.load(settingsFile());
        this.controller = new ShowController(new JavaSoundPlayer(), this);
        controller.setVolume(settings.volume());
        buildUi();
        applySettings(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                controller.close();
                worker.shutdownNow();
                System.exit(0);
            }
        });
        setSize(900, 700);
        setMinimumSize(new Dimension(600, 450));
        setLocationRelativeTo(null);
    }

    private Path settingsFile() {
        return homeDir.resolve("settings.properties");
    }

    private Path cacheDir() {
        return homeDir.resolve("cache");
    }

    // --- building --------------------------------------------------------

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(root);

        // Top: where the files come from, and the few setup actions.
        JPanel top = new JPanel(new BorderLayout(8, 0));
        sourceLabel.setFont(sourceLabel.getFont().deriveFont(Font.PLAIN, 13f));
        top.add(sourceLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton local = new JButton("Cartella locale…");
        local.addActionListener(e -> chooseLocalFolder());
        JButton drive = new JButton("Google Drive…");
        drive.addActionListener(e -> chooseDriveFolder());
        JButton refresh = new JButton("Aggiorna");
        refresh.addActionListener(e -> refreshAndDownload());
        docsButton.addActionListener(e -> showDocuments());
        actions.add(docsButton);
        actions.add(local);
        actions.add(drive);
        actions.add(refresh);
        top.add(actions, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        // Centre: the list, big.
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(list.getFont().deriveFont(Font.PLAIN, 22f));
        list.setFixedCellHeight(44);
        list.setCellRenderer(new CueRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncingSelection && list.getSelectedIndex() >= 0) {
                controller.select(list.getSelectedIndex());
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    go();
                }
            }
        });
        root.add(new JScrollPane(list), BorderLayout.CENTER);

        // Bottom: transport.
        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        nowPlaying.setFont(nowPlaying.getFont().deriveFont(Font.BOLD, 18f));
        nowPlaying.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.add(nowPlaying);
        bottom.add(Box.createVerticalStrut(4));

        JPanel progressRow = new JPanel(new BorderLayout(8, 0));
        progressRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setPreferredSize(new Dimension(100, 14));
        progressRow.add(progress, BorderLayout.CENTER);
        time.setFont(time.getFont().deriveFont(Font.PLAIN, 14f));
        progressRow.add(time, BorderLayout.EAST);
        bottom.add(progressRow);
        bottom.add(Box.createVerticalStrut(8));

        JPanel buttons = new JPanel(new GridLayout(1, 3, 8, 0));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        goButton.addActionListener(e -> go());
        stopButton.addActionListener(e -> run(controller::stop));
        pauseButton.addActionListener(e -> run(controller::pauseOrResume));
        buttons.add(goButton);
        buttons.add(pauseButton);
        buttons.add(stopButton);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        bottom.add(buttons);
        bottom.add(Box.createVerticalStrut(8));

        JPanel volumeRow = new JPanel(new BorderLayout(8, 0));
        volumeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel volLabel = new JLabel("Volume");
        volumeRow.add(volLabel, BorderLayout.WEST);
        JSlider volume = new JSlider(0, 100, (int) Math.round(settings.volume() * 100));
        volume.addChangeListener(e -> {
            double v = volume.getValue() / 100.0;
            controller.setVolume(v);
            settings.setVolume(v);
            if (!volume.getValueIsAdjusting()) {
                saveSettings();
            }
        });
        volumeRow.add(volume, BorderLayout.CENTER);
        bottom.add(volumeRow);
        bottom.add(Box.createVerticalStrut(4));

        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setFont(status.getFont().deriveFont(Font.PLAIN, 12f));
        bottom.add(status);
        root.add(bottom, BorderLayout.SOUTH);

        // Keys that work wherever the focus is: space = GO, escape = STOP, P = pause.
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "go", this::go);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stop", () -> run(controller::stop));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "pause", () -> run(controller::pauseOrResume));
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "go");
        list.getActionMap().put("go", action(this::go));

        new Timer(200, e -> updateProgress()).start();
    }

    private static JButton bigButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 26f));
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setPreferredSize(new Dimension(200, 80));
        return b;
    }

    private static void bind(JComponent c, KeyStroke key, String name, Runnable r) {
        c.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        c.getActionMap().put(name, action(r));
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        };
    }

    // --- source handling ---------------------------------------------------

    /** Builds the source from the settings and, unless silent, lists and downloads it. */
    private void applySettings(boolean refresh) {
        LibrarySource source;
        if (settings.mode() == Settings.Mode.LOCAL && !settings.localFolder().isEmpty()) {
            source = new LocalFolderSource(Paths.get(settings.localFolder()));
            sourceLabel.setText("Cartella: " + settings.localFolder());
        } else {
            source = new DriveFolderSource(new DriveClient(HttpTransport.DEFAULT, Defaults.DRIVE_API_KEY),
                    settings.driveFolderId(), cacheDir());
            sourceLabel.setText("Google Drive: " + DriveLinks.folderUrl(settings.driveFolderId()));
        }
        controller.setSource(source);
        if (refresh) {
            refreshAndDownload();
        }
    }

    private void refreshAndDownload() {
        setStatus("Aggiorno l'elenco…");
        run(() -> {
            controller.refresh();
            controller.downloadAll((what, done, total) -> SwingUtilities.invokeLater(() ->
                    setStatus("Scarico " + what + (total > 0 ? "  " + (done * 100 / total) + "%" : ""))));
            SwingUtilities.invokeLater(() -> setStatus(controller.library().cues().size() + " brani pronti"));
        });
    }

    private void chooseLocalFolder() {
        JFileChooser chooser = new JFileChooser(settings.localFolder().isEmpty() ? null : settings.localFolder());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Scegli la cartella con le musiche");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settings.setMode(Settings.Mode.LOCAL);
            settings.setLocalFolder(chooser.getSelectedFile().getAbsolutePath());
            saveSettings();
            run(controller::stop);
            applySettings(true);
        }
    }

    private void chooseDriveFolder() {
        String answer = (String) JOptionPane.showInputDialog(this,
                "Incolla il link della cartella di Google Drive\n(la cartella deve essere condivisa con \"chiunque abbia il link\"):",
                "Google Drive", JOptionPane.PLAIN_MESSAGE, null, null, DriveLinks.folderUrl(settings.driveFolderId()));
        if (answer == null) {
            return;
        }
        String id = DriveLinks.folderId(answer);
        if (id == null) {
            JOptionPane.showMessageDialog(this, "Non sembra un link di una cartella di Google Drive.", "Google Drive",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        settings.setMode(Settings.Mode.DRIVE);
        settings.setDriveFolderId(id);
        saveSettings();
        run(controller::stop);
        applySettings(true);
    }

    private void showDocuments() {
        Library lib = controller.library();
        JPopupMenu menu = new JPopupMenu();
        if (lib.documents().isEmpty()) {
            JMenuItem none = new JMenuItem("Nessun documento nella cartella");
            none.setEnabled(false);
            menu.add(none);
        }
        for (Document d : lib.documents()) {
            JMenuItem item = new JMenuItem(d.name());
            item.addActionListener(e -> openDocument(d));
            menu.add(item);
        }
        menu.show(docsButton, 0, docsButton.getHeight());
    }

    private void openDocument(Document d) {
        run(() -> {
            try {
                URI uri = URI.create(d.link());
                if ("file".equals(uri.getScheme())) {
                    Desktop.getDesktop().open(new File(uri));
                } else {
                    Desktop.getDesktop().browse(uri);
                }
            } catch (IOException | RuntimeException e) {
                error("Non riesco ad aprire " + d.name() + ": " + e.getMessage());
            }
        });
    }

    private void saveSettings() {
        try {
            settings.save(settingsFile());
        } catch (IOException e) {
            error("Non riesco a salvare le impostazioni: " + e.getMessage());
        }
    }

    // --- transport -------------------------------------------------------

    private void go() {
        Cue cue = controller.selectedCue();
        if (cue != null && !controller.source().isLocallyAvailable(cue)) {
            setStatus("Scarico " + cue.title() + "…");
        }
        run(controller::go);
    }

    private void run(Runnable r) {
        worker.execute(() -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                error(e.toString());
            }
        });
    }

    // --- rendering -------------------------------------------------------

    @Override
    public void changed() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::changed);
            return;
        }
        Library lib = controller.library();
        syncingSelection = true;
        try {
            if (model.size() != lib.cues().size() || !sameCues(lib)) {
                model.clear();
                for (Cue c : lib.cues()) {
                    model.addElement(c);
                }
            }
            int sel = controller.selectedIndex();
            if (sel != list.getSelectedIndex()) {
                if (sel >= 0) {
                    list.setSelectedIndex(sel);
                    list.ensureIndexIsVisible(sel);
                } else {
                    list.clearSelection();
                }
            }
        } finally {
            syncingSelection = false;
        }
        list.repaint();

        ShowController.State state = controller.state();
        Cue playing = controller.playingCue();
        switch (state) {
            case PLAYING:
                nowPlaying.setText("▶  " + playing.title());
                nowPlaying.setForeground(GREEN);
                break;
            case PAUSED:
                nowPlaying.setText("❚❚  " + playing.title() + "  (in pausa)");
                nowPlaying.setForeground(Color.DARK_GRAY);
                break;
            case LOADING:
                nowPlaying.setText("…  " + playing.title());
                nowPlaying.setForeground(Color.DARK_GRAY);
                break;
            case FADING:
                nowPlaying.setText("▼  " + playing.title() + "  (sfuma…)");
                nowPlaying.setForeground(RED);
                break;
            default:
                Cue next = controller.selectedCue();
                nowPlaying.setText(next == null ? " " : "Prossimo:  " + next.title());
                nowPlaying.setForeground(Color.DARK_GRAY);
        }
        pauseButton.setText(state == ShowController.State.PAUSED ? "▶  RIPRENDI" : "❚❚  PAUSA");
        pauseButton.setEnabled(state == ShowController.State.PLAYING || state == ShowController.State.PAUSED);
        stopButton.setEnabled(state != ShowController.State.IDLE);
        goButton.setEnabled(controller.selectedCue() != null);
        updateProgress();
    }

    private boolean sameCues(Library lib) {
        for (int i = 0; i < model.size(); i++) {
            if (!model.get(i).equals(lib.cues().get(i))) {
                return false;
            }
        }
        return true;
    }

    private void updateProgress() {
        long pos = controller.positionMillis();
        long dur = controller.durationMillis();
        if (controller.state() == ShowController.State.IDLE) {
            progress.setValue(0);
            time.setText(" ");
            return;
        }
        progress.setValue(dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : 0);
        time.setText(clock(pos) + (dur > 0 ? " / " + clock(dur) : ""));
    }

    static String clock(long millis) {
        long s = Math.max(0, millis / 1000);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public void error(String message) {
        SwingUtilities.invokeLater(() -> {
            status.setForeground(RED);
            status.setText(message);
        });
    }

    private void setStatus(String message) {
        status.setForeground(Color.DARK_GRAY);
        status.setText(message);
    }

    /** A row: a coloured tag for the kind, the title, and whether the file is already on disk. */
    private final class CueRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index, boolean selected,
                                                      boolean focused) {
            Cue cue = (Cue) value;
            JLabel label = (JLabel) super.getListCellRendererComponent(l, cue.title(), index, selected, focused);
            boolean playing = index == controller.playingIndex();
            boolean cached = controller.source() != null && controller.source().isLocallyAvailable(cue);
            String prefix = playing ? "▶  " : "    ";
            String suffix = cached ? "" : "   ⤓";
            label.setText(prefix + cue.title() + suffix);
            label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            if (!selected) {
                label.setBackground(playing ? PLAYING_BG : Color.WHITE);
                label.setForeground(cue.kind() == Cue.Kind.MUSIC ? MUSIC
                        : cue.kind() == Cue.Kind.EFFECT ? EFFECT : Color.BLACK);
                if (!cached) {
                    label.setForeground(Color.GRAY);
                }
            }
            return label;
        }
    }
}
