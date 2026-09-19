package org.bunnys.utils;

/**
 * Discord inline timestamp markup.
 *
 * <p>The house rule is that a duration or an instant a user reads is always markup and
 * never a string this bot rendered: {@code <t:UNIX:R>} for anything live, {@code <t:UNIX:F>}
 * for an absolute moment. Discord re-renders both client-side, in the reader's own locale
 * and timezone, every time the message is looked at - where a baked-in "3 hours ago" is
 * wrong the moment somebody scrolls back to it.
 *
 * <p>This is the one place that spells the format codes out. They were previously written
 * by hand at every call site, which is a rule enforced by everybody remembering it; a
 * single helper is a rule enforced by the compiler picking a {@link Format} from a fixed
 * set.
 *
 * <p>Salvaged from the unused {@code Utils} class, which is otherwise gone. Nothing else
 * in it survived the move: its duration formatters rendered strings, which is the exact
 * thing this file exists to avoid, and its permission and list helpers were all
 * superseded by better versions already in the embed code.
 */
public final class Timestamps {

    /**
     * Discord's timestamp style codes.
     *
     * <pre>
     *  SHORT_DATE      'd'  →  30/01/2025
     *  LONG_DATE       'D'  →  30 January 2025
     *  SHORT_TIME      't'  →  12:00
     *  LONG_TIME       'T'  →  12:00:00
     *  SHORT_DATETIME  'f'  →  30 January 2025 12:00
     *  LONG_DATETIME   'F'  →  Thursday, 30 January 2025 12:00
     *  RELATIVE        'R'  →  5 minutes ago
     * </pre>
     */
    public enum Format {
        SHORT_DATE('d'),
        LONG_DATE('D'),
        SHORT_TIME('t'),
        LONG_TIME('T'),
        SHORT_DATETIME('f'),
        LONG_DATETIME('F'),
        RELATIVE('R');

        private final char code;

        Format(char code) {
            this.code = code;
        }

        public char code() {
            return code;
        }
    }

    private Timestamps() {}

    /** Markup for a UNIX second, in the given style. */
    public static String of(long epochSeconds, Format format) {
        return "<t:" + epochSeconds + ":" + format.code() + ">";
    }

    /** A live relative counter: "in 3 seconds", "5 minutes ago". The default for anything ticking. */
    public static String relative(long epochSeconds) {
        return of(epochSeconds, Format.RELATIVE);
    }

    /** A full absolute moment, for something that happened once and stays put. */
    public static String absolute(long epochSeconds) {
        return of(epochSeconds, Format.LONG_DATETIME);
    }

    /**
     * Both: the absolute moment, then the live counter beneath it.
     *
     * <p>What a profile or an uptime card wants - the exact date for the record, and the
     * "how long ago" that a reader actually parses at a glance.
     */
    public static String absoluteAndRelative(long epochSeconds) {
        return absolute(epochSeconds) + "\n" + relative(epochSeconds);
    }
}
