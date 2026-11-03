package com.nexora.reports.render.xlsx;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Counts and digests (SHA-256, EX-17) the bytes it passes on, and refuses the first write that would take them past
 * the limit (precision 4): the file being written can never grow beyond it.
 */
final class LimitedDigestOutputStream extends FilterOutputStream {

    /** The limit was reached: the file is incomplete and must be discarded. */
    static final class LimitExceededException extends IOException {
        private static final long serialVersionUID = 1L;

        LimitExceededException(long limit) {
            super("artifact over " + limit + " bytes");
        }
    }

    private final long limit;
    private final MessageDigest digest;
    private long count;
    private boolean exceeded;

    LimitedDigestOutputStream(OutputStream out, long limit) {
        super(out);
        this.limit = limit;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void write(int b) throws IOException {
        reserve(1);
        out.write(b);
        digest.update((byte) b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        reserve(len);
        out.write(b, off, len);
        digest.update(b, off, len);
    }

    private void reserve(int len) throws LimitExceededException {
        if (exceeded || count + len > limit) {
            exceeded = true;
            throw new LimitExceededException(limit);
        }
        count += len;
    }

    long count() {
        return count;
    }

    boolean exceeded() {
        return exceeded;
    }

    /** The SHA-256 of every byte written, in lowercase hexadecimal; call once, after closing. */
    String sha256() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
