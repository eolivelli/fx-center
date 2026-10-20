package dev.olivelli.fxcenter.core;

import java.util.Locale;

/**
 * Case-insensitive ordering where runs of digits compare numerically, so that
 * {@code MUS-2} sorts before {@code MUS-10}. The show order is the file-name
 * order, and the cue register numbers cues with two digits — but people do
 * not always pad.
 */
public final class NaturalOrder {

    private NaturalOrder() {
    }

    public static int compare(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i;
                int sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) {
                    i++;
                }
                while (j < y.length() && Character.isDigit(y.charAt(j))) {
                    j++;
                }
                String nx = stripZeros(x.substring(si, i));
                String ny = stripZeros(y.substring(sj, j));
                if (nx.length() != ny.length()) {
                    return nx.length() - ny.length();
                }
                int c = nx.compareTo(ny);
                if (c != 0) {
                    return c;
                }
            } else {
                if (cx != cy) {
                    return cx - cy;
                }
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }
}
