package dev.olivelli.fxcenter.core;

/** Receives download progress. Called from whatever thread does the work. */
public interface Progress {

    Progress NONE = (what, done, total) -> { };

    /**
     * @param what  the file being transferred
     * @param done  bytes so far
     * @param total expected bytes, or -1 when unknown
     */
    void update(String what, long done, long total);
}
