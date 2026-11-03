package dev.olivelli.fxcenter.android;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.ShowController;

/**
 * Rows of the cue list, as on the desktop: a stripe for the kind (amber while
 * it plays), the position in the show, the title, greyed until the file is on
 * the phone.
 */
final class CueAdapter extends ArrayAdapter<Cue> {

    private final ShowController controller;
    /** Where in the library each row is: every cue, unless the search box filters. */
    private int[] visible = new int[0];

    CueAdapter(Context context, ShowController controller) {
        super(context, R.layout.row_cue);
        this.controller = controller;
    }

    void setVisible(int[] visible) {
        this.visible = visible;
    }

    /** The library position of a row, or -1. */
    int cueAt(int row) {
        return row >= 0 && row < visible.length ? visible[row] : -1;
    }

    /** The row showing the cue at a library position, or -1 if the filter hides it. */
    int rowOf(int index) {
        for (int i = 0; i < visible.length; i++) {
            if (visible[i] == index) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView != null ? convertView
                : LayoutInflater.from(getContext()).inflate(R.layout.row_cue, parent, false);
        Context c = getContext();
        Cue cue = getItem(position);
        int index = cueAt(position); // where it is in the show, whatever the filter
        boolean playing = index == controller.playingIndex();
        boolean selected = index == controller.selectedIndex();
        boolean cached = controller.source() != null && controller.source().isLocallyAvailable(cue);
        int kind = c.getColor(cue.kind() == Cue.Kind.MUSIC ? R.color.music
                : cue.kind() == Cue.Kind.EFFECT ? R.color.effect : R.color.text_dim);

        row.setBackgroundColor(c.getColor(playing ? R.color.accent_bg : selected ? R.color.selection : R.color.surface));
        row.findViewById(R.id.stripe).setBackgroundColor(playing ? c.getColor(R.color.accent)
                : selected ? kind : dim(kind));

        TextView number = row.findViewById(R.id.number);
        number.setText(numberText(index, playing));
        number.setTextColor(c.getColor(playing ? R.color.accent : R.color.text_faint));

        TextView title = row.findViewById(R.id.title);
        title.setText(titleText(cue, cached));
        title.setTextColor(c.getColor(playing ? R.color.accent : !cached ? R.color.text_dim : R.color.text));
        title.setTypeface(null, playing || selected ? Typeface.BOLD : Typeface.NORMAL);
        return row;
    }

    static String numberText(int position, boolean playing) {
        return playing ? "▶" : String.valueOf(position + 1);
    }

    static String titleText(Cue cue, boolean cached) {
        return cue.title() + (cached ? "" : "   ⤓");
    }

    /** The stripe of a row that is neither selected nor playing: present, but quiet. */
    private static int dim(int color) {
        return (color & 0xff000000) | ((color >> 2) & 0x3f3f3f) + 0x0a0a0a;
    }
}
