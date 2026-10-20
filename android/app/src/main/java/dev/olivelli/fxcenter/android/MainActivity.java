package dev.olivelli.fxcenter.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.Defaults;
import dev.olivelli.fxcenter.core.Document;
import dev.olivelli.fxcenter.core.DriveClient;
import dev.olivelli.fxcenter.core.DriveFolderSource;
import dev.olivelli.fxcenter.core.DriveLinks;
import dev.olivelli.fxcenter.core.HttpTransport;
import dev.olivelli.fxcenter.core.Library;
import dev.olivelli.fxcenter.core.Settings;
import dev.olivelli.fxcenter.core.ShowController;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one screen. Mirrors the desktop window: the list, GO / PAUSA / STOP,
 * volume, and a small setup row on top. Blocking work runs on {@link #worker};
 * the controller's callbacks are posted to the main thread.
 */
public final class MainActivity extends Activity implements ShowController.Listener {

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Settings settings;
    private ShowController controller;
    private CueAdapter adapter;

    private ListView list;
    private TextView source;
    private TextView nowPlaying;
    private TextView time;
    private TextView status;
    private ProgressBar progress;
    private Button go;
    private Button pause;
    private Button stop;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            main.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // Edge to edge on Android 15: pad the content out from under the bars.
        View content = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
        // A phone that locks mid-scene is worse than a phone that stays lit.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        settings = Settings.load(settingsFile());
        controller = new ShowController(new AndroidPlayer(), this);
        controller.setVolume(settings.volume());
        adapter = new CueAdapter(this, controller);

        list = findViewById(R.id.cues);
        source = findViewById(R.id.source);
        nowPlaying = findViewById(R.id.now_playing);
        time = findViewById(R.id.time);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        go = findViewById(R.id.go);
        pause = findViewById(R.id.pause);
        stop = findViewById(R.id.stop);

        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> controller.select(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            controller.select(position);
            doGo();
            return true;
        });
        go.setOnClickListener(v -> doGo());
        stop.setOnClickListener(v -> run(controller::stop));
        pause.setOnClickListener(v -> run(controller::pauseOrResume));
        findViewById(R.id.refresh).setOnClickListener(v -> refreshAndDownload());
        findViewById(R.id.drive).setOnClickListener(v -> chooseDriveFolder());
        findViewById(R.id.documents).setOnClickListener(v -> showDocuments());

        SeekBar volume = findViewById(R.id.volume);
        volume.setProgress((int) Math.round(settings.volume() * 100));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                controller.setVolume(value / 100.0);
                settings.setVolume(value / 100.0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                saveSettings();
            }
        });

        applySettings();
        main.post(ticker);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        controller.close();
        worker.shutdownNow();
        super.onDestroy();
    }

    private Path homeDir() {
        return getFilesDir().toPath().resolve("fx-center");
    }

    private Path settingsFile() {
        return homeDir().resolve("settings.properties");
    }

    private void applySettings() {
        controller.setSource(new DriveFolderSource(new DriveClient(HttpTransport.DEFAULT, Defaults.DRIVE_API_KEY),
                settings.driveFolderId(), homeDir().resolve("cache")));
        source.setText("Google Drive: " + DriveLinks.folderUrl(settings.driveFolderId()));
        refreshAndDownload();
    }

    private void refreshAndDownload() {
        setStatus("Aggiorno l'elenco…");
        run(() -> {
            controller.refresh();
            controller.downloadAll((what, done, total) -> main.post(() ->
                    setStatus("Scarico " + what + (total > 0 ? "  " + (done * 100 / total) + "%" : ""))));
            main.post(() -> setStatus(controller.library().cues().size() + " brani pronti"));
        });
    }

    private void chooseDriveFolder() {
        EditText input = new EditText(this);
        input.setText(DriveLinks.folderUrl(settings.driveFolderId()));
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.drive)
                .setMessage(R.string.drive_prompt)
                .setView(input)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    String id = DriveLinks.folderId(input.getText().toString());
                    if (id == null) {
                        Toast.makeText(this, R.string.drive_bad_link, Toast.LENGTH_LONG).show();
                        return;
                    }
                    settings.setDriveFolderId(id);
                    saveSettings();
                    run(controller::stop);
                    applySettings();
                })
                .show();
    }

    private void showDocuments() {
        List<Document> docs = controller.library().documents();
        if (docs.isEmpty()) {
            Toast.makeText(this, R.string.no_documents, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[docs.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = docs.get(i).name();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.documents)
                .setItems(names, (d, which) -> {
                    // The Drive link opens in the Docs/Drive app when installed, else the browser.
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(docs.get(which).link()));
                    try {
                        startActivity(intent);
                    } catch (RuntimeException e) {
                        error("Nessuna app per aprire " + docs.get(which).name());
                    }
                })
                .show();
    }

    private void saveSettings() {
        try {
            settings.save(settingsFile());
        } catch (IOException e) {
            error("Non riesco a salvare le impostazioni: " + e.getMessage());
        }
    }

    private void doGo() {
        Cue cue = controller.selectedCue();
        if (cue != null && controller.source() != null && !controller.source().isLocallyAvailable(cue)) {
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

    // --- ShowController.Listener -------------------------------------------

    @Override
    public void changed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::changed);
            return;
        }
        Library lib = controller.library();
        if (adapter.getCount() != lib.cues().size() || !sameCues(lib)) {
            adapter.setNotifyOnChange(false);
            adapter.clear();
            adapter.addAll(lib.cues());
        }
        adapter.notifyDataSetChanged();
        int sel = controller.selectedIndex();
        if (sel >= 0 && (sel < list.getFirstVisiblePosition() || sel > list.getLastVisiblePosition())) {
            list.smoothScrollToPosition(sel);
        }

        ShowController.State state = controller.state();
        Cue playing = controller.playingCue();
        switch (state) {
            case PLAYING:
                nowPlaying.setText("▶  " + playing.title());
                nowPlaying.setTextColor(getColor(R.color.go));
                break;
            case PAUSED:
                nowPlaying.setText("❚❚  " + playing.title() + "  (in pausa)");
                nowPlaying.setTextColor(getColor(R.color.pause));
                break;
            case LOADING:
                nowPlaying.setText("…  " + playing.title());
                nowPlaying.setTextColor(getColor(R.color.pause));
                break;
            case FADING:
                nowPlaying.setText("▼  " + playing.title() + "  (sfuma…)");
                nowPlaying.setTextColor(getColor(R.color.stop));
                break;
            default:
                Cue next = controller.selectedCue();
                nowPlaying.setText(next == null ? " " : "Prossimo:  " + next.title());
                nowPlaying.setTextColor(getColor(R.color.pause));
        }
        pause.setText(state == ShowController.State.PAUSED ? R.string.resume : R.string.pause);
        pause.setEnabled(state == ShowController.State.PLAYING || state == ShowController.State.PAUSED);
        stop.setEnabled(state != ShowController.State.IDLE);
        go.setEnabled(controller.selectedCue() != null);
        updateProgress();
    }

    private boolean sameCues(Library lib) {
        for (int i = 0; i < adapter.getCount(); i++) {
            if (!adapter.getItem(i).equals(lib.cues().get(i))) {
                return false;
            }
        }
        return true;
    }

    private void updateProgress() {
        if (controller.state() == ShowController.State.IDLE) {
            progress.setProgress(0);
            time.setText(" ");
            return;
        }
        long pos = controller.positionMillis();
        long dur = controller.durationMillis();
        progress.setProgress(dur > 0 ? (int) Math.min(1000, pos * 1000 / dur) : 0);
        time.setText(clock(pos) + (dur > 0 ? " / " + clock(dur) : ""));
    }

    static String clock(long millis) {
        long s = Math.max(0, millis / 1000);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    @Override
    public void error(String message) {
        main.post(() -> {
            status.setTextColor(getColor(R.color.error));
            status.setText(message);
        });
    }

    private void setStatus(String message) {
        status.setTextColor(getColor(R.color.pause));
        status.setText(message);
    }
}
