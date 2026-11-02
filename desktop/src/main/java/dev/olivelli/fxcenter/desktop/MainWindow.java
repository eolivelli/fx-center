package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.Clip;
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
import dev.olivelli.fxcenter.core.Scaletta;
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
import java.awt.Cursor;
import java.awt.event.InputEvent;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ListCellRenderer;
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
    private final JLabel nowLabel = new JLabel("PROSSIMO");
    private final JLabel nowPlaying = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
    private final JLabel time = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton goButton = bigButton("▶  VAI", Theme.GO);
    private final JButton stopButton = bigButton("■  STOP", Theme.STOP);
    private final JButton pauseButton = bigButton("❚❚  PAUSA", Theme.PAUSE);
    private final JButton docsButton = new JButton("Documenti ▾");
    private final JButton editButton = new JButton("✎ Modifica scaletta");
    private final List<JButton> sourceButtons = new ArrayList<>();
    private final JPanel editBar = new JPanel(new BorderLayout(8, 0));
    private final JButton saveButton = new JButton("Salva");
    private boolean syncingSelection;

    private final GoogleLogin login;
    private final ScalettaStore store;
    /** The library as it was when editing began; null when not editing. */
    private Library beforeEdit;
    private boolean dirty;
    private int dragFrom = -1;

    MainWindow(Path homeDir) {
        super("FX Center");
        this.homeDir = homeDir;
        this.settings = Settings.load(settingsFile());
        this.login = new GoogleLogin(homeDir);
        this.store = new ScalettaStore(login);
        this.controller = new ShowController(new JavaSoundPlayer(), this);
        controller.setVolume(settings.volume());
        buildUi();
        applySettings(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (editing() && dirty && JOptionPane.showConfirmDialog(MainWindow.this,
                        "La scaletta ha modifiche non salvate. Uscire lo stesso?", "FX Center",
                        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                    return;
                }
                dispose();
            }

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

    /** For the manual screenshot checks. */
    ShowController controllerForTests() {
        return controller;
    }

    private Path settingsFile() {
        return homeDir.resolve("settings.properties");
    }

    private Path cacheDir() {
        return homeDir.resolve("cache");
    }

    // --- building --------------------------------------------------------

    private void buildUi() {
        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        root.setBackground(Theme.BG);
        setContentPane(root);

        // Top: the name, where the files come from, and the few setup actions.
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        JLabel brand = new JLabel("FX CENTER");
        brand.setFont(Theme.font(15f, Font.BOLD));
        brand.setForeground(Theme.ACCENT);
        top.add(brand, BorderLayout.WEST);
        sourceLabel.setFont(Theme.font(12f, Font.PLAIN));
        sourceLabel.setForeground(Theme.TEXT_DIM);
        top.add(sourceLabel, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        JButton local = toolButton("Cartella locale…");
        local.addActionListener(e -> chooseLocalFolder());
        JButton drive = toolButton("Google Drive…");
        drive.addActionListener(e -> chooseDriveFolder());
        JButton refresh = toolButton("⟳  Aggiorna");
        refresh.addActionListener(e -> refreshAndDownload());
        styleTool(docsButton);
        styleTool(editButton);
        docsButton.addActionListener(e -> showDocuments());
        editButton.setToolTipText("Cambia l'ordine dei brani e scegli quale parte di ciascuno suonare");
        editButton.addActionListener(e -> startEditing());
        actions.add(editButton);
        actions.add(docsButton);
        actions.add(local);
        actions.add(drive);
        actions.add(refresh);
        sourceButtons.add(local);
        sourceButtons.add(drive);
        sourceButtons.add(refresh);
        sourceButtons.add(editButton);
        top.add(actions, BorderLayout.EAST);

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(top);
        buildEditBar();
        editBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        north.add(Box.createVerticalStrut(6));
        north.add(editBar);
        root.add(north, BorderLayout.NORTH);

        // Centre: the list, big.
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(Theme.font(21f, Font.PLAIN));
        list.setFixedCellHeight(48);
        list.setBackground(Theme.SURFACE);
        list.setCellRenderer(new CueRenderer());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !syncingSelection && list.getSelectedIndex() >= 0) {
                controller.select(list.getSelectedIndex());
            }
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    if (editing()) {
                        editClip();
                    } else {
                        go();
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dragFrom = editing() ? list.locationToIndex(e.getPoint()) : -1;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragFrom < 0) {
                    return;
                }
                int to = list.locationToIndex(e.getPoint());
                if (to >= 0 && to != dragFrom) {
                    list.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    moveCue(dragFrom, to);
                    dragFrom = to;
                    list.ensureIndexIsVisible(to);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragFrom = -1;
                list.setCursor(Cursor.getDefaultCursor());
            }
        };
        list.addMouseListener(mouse);
        list.addMouseMotionListener(mouse);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        scroll.getViewport().setBackground(Theme.SURFACE);
        root.add(scroll, BorderLayout.CENTER);

        // Bottom: what plays (a card), then the transport.
        JPanel bottom = new JPanel();
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Theme.RAISED);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(8, 12, 10, 12)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        nowLabel.setFont(Theme.font(11f, Font.BOLD));
        nowLabel.setForeground(Theme.TEXT_DIM);
        nowLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(nowLabel);
        card.add(Box.createVerticalStrut(2));
        nowPlaying.setFont(Theme.font(20f, Font.BOLD));
        nowPlaying.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(nowPlaying);
        card.add(Box.createVerticalStrut(8));
        JPanel progressRow = new JPanel(new BorderLayout(10, 0));
        progressRow.setOpaque(false);
        progressRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        progress.setPreferredSize(new Dimension(100, 6));
        // Wrapped, so the bar keeps its thin height instead of the row's.
        JPanel bar = new JPanel(new java.awt.GridBagLayout());
        bar.setOpaque(false);
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.fill = java.awt.GridBagConstraints.HORIZONTAL;
        gc.weightx = 1;
        bar.add(progress, gc);
        progressRow.add(bar, BorderLayout.CENTER);
        time.setFont(Theme.font(14f, Font.PLAIN));
        time.setForeground(Theme.TEXT_DIM);
        progressRow.add(time, BorderLayout.EAST);
        card.add(progressRow);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height + 20));
        bottom.add(card);
        bottom.add(Box.createVerticalStrut(10));

        JPanel buttons = new JPanel(new GridLayout(1, 3, 10, 0));
        buttons.setOpaque(false);
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

        JPanel volumeRow = new JPanel(new BorderLayout(10, 0));
        volumeRow.setOpaque(false);
        volumeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel volLabel = new JLabel("VOLUME");
        volLabel.setFont(Theme.font(11f, Font.BOLD));
        volLabel.setForeground(Theme.TEXT_DIM);
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
        volume.setOpaque(false);
        volumeRow.add(volume, BorderLayout.CENTER);
        JLabel volValue = new JLabel(volume.getValue() + "%");
        volValue.setFont(Theme.font(12f, Font.PLAIN));
        volValue.setForeground(Theme.TEXT_DIM);
        volValue.setPreferredSize(new Dimension(40, volValue.getPreferredSize().height));
        volume.addChangeListener(e -> volValue.setText(volume.getValue() + "%"));
        volumeRow.add(volValue, BorderLayout.EAST);
        volumeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, volumeRow.getPreferredSize().height));
        bottom.add(volumeRow);
        bottom.add(Box.createVerticalStrut(6));

        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setFont(Theme.font(12f, Font.PLAIN));
        status.setForeground(Theme.TEXT_DIM);
        bottom.add(status);
        root.add(bottom, BorderLayout.SOUTH);

        // Keys that work wherever the focus is: space = GO, escape = STOP, P = pause.
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "go", this::go);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stop", () -> run(controller::stop));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_P, 0), "pause", () -> run(controller::pauseOrResume));
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "go");
        list.getActionMap().put("go", action(this::go));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.ALT_DOWN_MASK), "moveUp", () -> moveSelected(-1));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK), "moveDown", () -> moveSelected(1));
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "clip");
        list.getActionMap().put("clip", action(() -> {
            if (editing()) {
                editClip();
            }
        }));

        new Timer(200, e -> updateProgress()).start();
    }

    private static JButton bigButton(String text, Color bg) {
        JButton b = new JButton(text);
        b.setFont(Theme.font(24f, Font.BOLD));
        b.setBackground(bg);
        b.setForeground(Theme.ON_FILL);
        b.setFocusPainted(false);
        b.setFocusable(false);
        b.putClientProperty("JButton.buttonType", "roundRect");
        b.putClientProperty("FlatLaf.style", "arc: 12; borderWidth: 0; focusWidth: 0; innerFocusWidth: 0;"
                + " hoverBackground: lighten(" + Theme.hex(bg) + ",5%);"
                + " pressedBackground: lighten(" + Theme.hex(bg) + ",10%);"
                + " disabledBackground: " + Theme.hex(Theme.SURFACE) + ";"
                + " disabledText: " + Theme.hex(Theme.TEXT_FAINT));
        b.setPreferredSize(new Dimension(200, 78));
        return b;
    }

    private static JButton toolButton(String text) {
        JButton b = new JButton(text);
        styleTool(b);
        return b;
    }

    /** The small buttons of the top bar: quiet, so the eye goes to the list and the transport. */
    private static void styleTool(JButton b) {
        b.setFont(Theme.font(13f, Font.PLAIN));
        b.setFocusable(false);
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

    // --- editing the scaletta -------------------------------------------

    private void buildEditBar() {
        editBar.setBackground(Theme.ACCENT_BG);
        editBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.ACCENT),
                BorderFactory.createEmptyBorder(8, 12, 8, 10)));
        JLabel hint = new JLabel("<html><font color=" + Theme.hex(Theme.ACCENT) + "><b>MODIFICA SCALETTA</b></font><br>"
                + "Trascina un brano per spostarlo (o Alt+↑/↓). Doppio clic o ✂ per scegliere la parte da suonare.</html>");
        hint.setFont(Theme.font(13f, Font.PLAIN));
        hint.setForeground(Theme.TEXT);
        editBar.add(hint, BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        b.setOpaque(false);
        JButton up = new JButton("▲");
        up.setToolTipText("Sposta su (Alt+↑)");
        up.addActionListener(e -> moveSelected(-1));
        JButton down = new JButton("▼");
        down.setToolTipText("Sposta giù (Alt+↓)");
        down.addActionListener(e -> moveSelected(1));
        JButton clip = new JButton("✂ Taglia…");
        clip.setToolTipText("Scegli da dove a dove suona il brano selezionato (Invio)");
        clip.addActionListener(e -> editClip());
        JButton cancel = new JButton("Annulla");
        cancel.addActionListener(e -> cancelEditing());
        saveButton.setFont(Theme.font(13f, Font.BOLD));
        saveButton.putClientProperty("FlatLaf.style", "background: " + Theme.hex(Theme.ACCENT.darker())
                + "; foreground: " + Theme.hex(Theme.BG) + "; borderWidth: 0;"
                + " hoverBackground: " + Theme.hex(Theme.ACCENT) + "; disabledBackground: " + Theme.hex(Theme.RAISED));
        for (JButton x : new JButton[] {up, down, clip, cancel}) {
            styleTool(x);
        }
        saveButton.setFocusable(false);
        saveButton.addActionListener(e -> saveEditing(false));
        b.add(up);
        b.add(down);
        b.add(clip);
        b.add(Box.createHorizontalStrut(16));
        b.add(cancel);
        b.add(saveButton);
        editBar.add(b, BorderLayout.EAST);
        editBar.setVisible(false);
    }

    private boolean editing() {
        return beforeEdit != null;
    }

    void startEditing() {
        if (editing()) {
            return;
        }
        beforeEdit = controller.library();
        dirty = false;
        updateEditUi();
    }

    private void updateEditUi() {
        boolean on = editing();
        editBar.setVisible(on);
        for (JButton b : sourceButtons) {
            b.setEnabled(!on);
        }
        saveButton.setEnabled(on && dirty);
        // Always say where it writes: Drive and a local folder behave differently.
        boolean drive = controller.source() instanceof DriveFolderSource;
        boolean login = drive && store.needsLogin(controller.source());
        saveButton.setText(drive ? (login ? "Salva su Drive…" : "Salva su Drive") : "Salva nella cartella");
        saveButton.setToolTipText(!dirty ? "Nessuna modifica da salvare: sposta o taglia un brano"
                : login ? "La prima volta si apre il browser per accedere a Google"
                : "Scrive " + Scaletta.FILE_NAME + (drive ? " nella cartella di Google Drive" : " nella cartella"));
        revalidate();
        list.repaint();
    }

    private void cancelEditing() {
        if (dirty && JOptionPane.showConfirmDialog(this, "Buttare via le modifiche alla scaletta?", "Annulla",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        Library original = beforeEdit;
        beforeEdit = null;
        controller.setLibrary(original);
        updateEditUi();
    }

    /** The running order while editing: pushed into the controller, so GO already plays the new version. */
    private void applyDraft(List<Cue> cues) {
        controller.setLibrary(controller.library().withCues(cues));
        dirty = true;
        updateEditUi();
    }

    private void moveCue(int from, int to) {
        if (!editing()) {
            return;
        }
        List<Cue> cues = new ArrayList<>(controller.library().cues());
        if (from < 0 || from >= cues.size() || to < 0 || to >= cues.size()) {
            return;
        }
        cues.add(to, cues.remove(from));
        applyDraft(cues);
        controller.select(to);
    }

    private void moveSelected(int delta) {
        int i = controller.selectedIndex();
        if (editing() && i >= 0) {
            moveCue(i, i + delta);
        }
    }

    private void editClip() {
        Cue cue = controller.selectedCue();
        if (!editing() || cue == null) {
            return;
        }
        LibrarySource source = controller.source();
        int index = controller.selectedIndex();
        setStatus("Preparo " + cue.title() + "…");
        run(() -> {
            if (controller.state() != ShowController.State.IDLE) {
                controller.stop();
            }
            Path file;
            try {
                file = source.localFile(cue, dev.olivelli.fxcenter.core.Progress.NONE);
            } catch (IOException e) {
                error("Non riesco a preparare " + cue.title() + ": " + e.getMessage());
                return;
            }
            SwingUtilities.invokeLater(() -> {
                setStatus(" ");
                Clip clip = ClipEditor.edit(this, cue, file);
                if (clip == null || clip.equals(cue.clip()) || !editing()) {
                    return;
                }
                List<Cue> cues = new ArrayList<>(controller.library().cues());
                if (index < cues.size() && cues.get(index).id().equals(cue.id())) {
                    cues.set(index, cues.get(index).withClip(clip));
                    applyDraft(cues);
                }
            });
        });
    }

    private void saveEditing(boolean force) {
        if (!editing() || !dirty) {
            return;
        }
        Library draft = controller.library();
        LibrarySource source = controller.source();
        Library.ScalettaInfo read = beforeEdit.scaletta();
        String text = Scaletta.format(draft.cues(), read.orphanLines());
        saveButton.setEnabled(false);
        setStatus(store.needsLogin(source)
                ? "Si apre il browser: accedi a Google per salvare la scaletta…" : "Salvo la scaletta…");
        run(() -> {
            try {
                store.save(source, read, text, force);
            } catch (ScalettaStore.Conflict e) {
                SwingUtilities.invokeLater(this::askOnConflict);
                return;
            } catch (IOException e) {
                error("Scaletta non salvata: " + e.getMessage());
                SwingUtilities.invokeLater(this::updateEditUi);
                return;
            }
            SwingUtilities.invokeLater(() -> {
                beforeEdit = null;
                updateEditUi();
                setStatus("Scaletta salvata");
            });
            controller.refresh();
        });
    }

    private void askOnConflict() {
        Object[] options = {"Sovrascrivi", "Ricarica (perdo le mie modifiche)", "Annulla"};
        int choice = JOptionPane.showOptionDialog(this,
                Scaletta.FILE_NAME + " è stato cambiato da qualcun altro mentre lo modificavi.\n"
                        + "Sovrascrivere le sue modifiche con le tue, o ricaricare la sua versione?",
                "Scaletta cambiata", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
                options[2]);
        if (choice == 0) {
            saveEditing(true);
        } else if (choice == 1) {
            beforeEdit = null;
            updateEditUi();
            refreshAndDownload();
        } else {
            updateEditUi();
        }
    }

    // --- source handling ---------------------------------------------------

    /** Builds the source from the settings and, unless silent, lists and downloads it. */
    private void applySettings(boolean refresh) {
        LibrarySource source;
        if (settings.mode() == Settings.Mode.LOCAL && !settings.localFolder().isEmpty()) {
            source = new LocalFolderSource(Paths.get(settings.localFolder()));
            sourceLabel.setText("Cartella  " + Paths.get(settings.localFolder()).getFileName());
            sourceLabel.setToolTipText(settings.localFolder());
        } else {
            source = new DriveFolderSource(new DriveClient(HttpTransport.DEFAULT, Defaults.DRIVE_API_KEY),
                    settings.driveFolderId(), cacheDir());
            sourceLabel.setText("Google Drive");
            sourceLabel.setToolTipText(DriveLinks.folderUrl(settings.driveFolderId()));
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
            SwingUtilities.invokeLater(() -> {
                Library lib = controller.library();
                List<String> warnings = lib.scaletta().warnings();
                if (warnings.isEmpty()) {
                    setStatus(lib.cues().size() + " brani pronti");
                } else {
                    // Keep the scaletta's problems on screen: they are why a cue is not where expected.
                    error(lib.cues().size() + " brani pronti. " + Scaletta.FILE_NAME + ": "
                            + String.join("; ", warnings));
                }
            });
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
                nowLabel.setText("IN ONDA");
                nowLabel.setForeground(Theme.ACCENT);
                nowPlaying.setText("▶  " + playing.title());
                nowPlaying.setForeground(Theme.ACCENT);
                break;
            case PAUSED:
                nowLabel.setText("IN PAUSA");
                nowLabel.setForeground(Theme.TEXT_DIM);
                nowPlaying.setText("❚❚  " + playing.title());
                nowPlaying.setForeground(Theme.TEXT);
                break;
            case LOADING:
                nowLabel.setText("CARICO…");
                nowLabel.setForeground(Theme.TEXT_DIM);
                nowPlaying.setText(playing.title());
                nowPlaying.setForeground(Theme.TEXT_DIM);
                break;
            case FADING:
                nowLabel.setText("SFUMA…");
                nowLabel.setForeground(Theme.ERROR);
                nowPlaying.setText("▼  " + playing.title());
                nowPlaying.setForeground(Theme.TEXT);
                break;
            default:
                Cue next = controller.selectedCue();
                nowLabel.setText("PROSSIMO");
                nowLabel.setForeground(Theme.TEXT_DIM);
                nowPlaying.setText(next == null ? " " : next.title());
                nowPlaying.setForeground(Theme.TEXT);
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
            status.setForeground(Theme.ERROR);
            status.setText(message);
            status.setToolTipText(message.length() > 80 ? message : null);
        });
    }

    private void setStatus(String message) {
        status.setForeground(Theme.TEXT_DIM);
        status.setText(message);
        status.setToolTipText(message.length() > 80 ? message : null);
    }

    /** "✂ 1:27" (how long it plays) or "✂ da 0:30" when it plays to the end of the file. */
    static String clipMark(Clip clip) {
        return clip.hasEnd() ? "✂ " + clock(clip.endMillis() - clip.startMillis())
                : "✂ da " + clock(clip.startMillis());
    }

    /**
     * A row: a stripe for the kind (amber while it plays), the position in the
     * show, the title, and on the right the clip and whether it still has to
     * be downloaded.
     */
    private final class CueRenderer extends JPanel implements ListCellRenderer<Cue> {
        private final JLabel number = new JLabel();
        private final JLabel title = new JLabel();
        private final JLabel meta = new JLabel();
        private Color stripe = Theme.BORDER;

        CueRenderer() {
            super(new BorderLayout(0, 0));
            number.setPreferredSize(new Dimension(58, 10));
            number.setHorizontalAlignment(JLabel.RIGHT);
            number.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 16));
            number.setFont(Theme.font(15f, Font.PLAIN));
            title.setFont(Theme.font(21f, Font.PLAIN));
            meta.setFont(Theme.font(15f, Font.PLAIN));
            meta.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 16));
            add(number, BorderLayout.WEST);
            add(title, BorderLayout.CENTER);
            add(meta, BorderLayout.EAST);
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BG));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Cue> l, Cue cue, int index, boolean selected,
                                                      boolean focused) {
            boolean playing = index == controller.playingIndex();
            boolean cached = controller.source() != null && controller.source().isLocallyAvailable(cue);
            Color kind = cue.kind() == Cue.Kind.MUSIC ? Theme.MUSIC
                    : cue.kind() == Cue.Kind.EFFECT ? Theme.EFFECT : Theme.TEXT_DIM;
            stripe = playing ? Theme.ACCENT : selected ? kind : kind.darker().darker();
            setBackground(playing ? Theme.ACCENT_BG : selected ? Theme.SELECTION : Theme.SURFACE);
            number.setText(playing ? "▶" : editing() ? "≡  " + (index + 1) : String.valueOf(index + 1));
            number.setForeground(playing ? Theme.ACCENT : Theme.TEXT_FAINT);
            title.setText(cue.title());
            title.setForeground(playing ? Theme.ACCENT : !cached ? Theme.TEXT_DIM : Theme.TEXT);
            title.setFont(title.getFont().deriveFont(playing || selected ? Font.BOLD : Font.PLAIN));
            String m = (cue.clip().isClipped() ? clipMark(cue.clip()) : "") + (cached ? "" : "   ⤓");
            meta.setText(m.trim());
            meta.setForeground(cue.clip().isClipped() ? Theme.ACCENT.darker() : Theme.TEXT_DIM);
            meta.setToolTipText(null);
            return this;
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            super.paintComponent(g);
            g.setColor(stripe);
            g.fillRect(0, 0, 4, getHeight());
        }
    }
}
