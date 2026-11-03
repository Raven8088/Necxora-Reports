package com.nexora.reports.translation2.domain;

/**
 * Defense in depth on the translated text, independent of how it was built. Outside quoted identifiers the text may
 * contain no statement separator ({@code ;}), no comment ({@code --}, {@code /*}), no string literal (no quote
 * {@code '} at all: every value is a parameter) and no dollar quoting; NUL is forbidden everywhere. Quoted
 * identifiers may legally contain any character except NUL, with embedded quotes doubled.
 */
public final class SqlTextGuard {

    private SqlTextGuard() {
    }

    public static void check(String sql) {
        boolean inIdentifier = false;
        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\0') {
                throw new IllegalStateException("translated SQL contains NUL");
            }
            if (ch == '"') {
                if (inIdentifier && i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                    i++;
                    continue;
                }
                inIdentifier = !inIdentifier;
                continue;
            }
            if (inIdentifier) {
                continue;
            }
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : ' ';
            if (ch == ';' || ch == '\'' || ch == '$' || ch == '\\' || (ch == '-' && next == '-')
                    || (ch == '/' && next == '*')) {
                throw new IllegalStateException("translated SQL contains a forbidden token outside identifiers");
            }
        }
        if (inIdentifier) {
            throw new IllegalStateException("translated SQL has an unterminated identifier");
        }
    }
}
