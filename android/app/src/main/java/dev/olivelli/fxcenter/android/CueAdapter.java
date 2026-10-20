package dev.olivelli.fxcenter.android;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.ShowController;

/** Rows of the cue list: title, coloured by kind, greyed until the file is on the phone. */
final class CueAdapter extends ArrayAdapter<Cue> {

    private final ShowController controller;

    CueAdapter(Context context, ShowController controller) {
        super(context, R.layout.row_cue);
        this.controller = controller;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView row = (TextView) (convertView != null ? convertView
                : LayoutInflater.from(getContext()).inflate(R.layout.row_cue, parent, false));
        Cue cue = getItem(position);
        boolean playing = position == controller.playingIndex();
        boolean selected = position == controller.selectedIndex();
        boolean cached = controller.source() != null && controller.source().isLocallyAvailable(cue);
        row.setText(rowText(cue, playing, cached));
        row.setTextColor(getContext().getColor(!cached ? R.color.missing
                : cue.kind() == Cue.Kind.MUSIC ? R.color.music
                : cue.kind() == Cue.Kind.EFFECT ? R.color.effect : R.color.other));
        row.setBackgroundColor(selected ? getContext().getColor(R.color.selected_bg)
                : playing ? getContext().getColor(R.color.playing_bg) : Color.WHITE);
        return row;
    }

    static String rowText(Cue cue, boolean playing, boolean cached) {
        return (playing ? "▶  " : "") + cue.title() + (cached ? "" : "   ⤓");
    }
}
