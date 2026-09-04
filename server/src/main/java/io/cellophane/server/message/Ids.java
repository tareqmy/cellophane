package io.cellophane.server.message;

import java.util.concurrent.ThreadLocalRandom;

/** ULID-style identifiers: 48 bits of time then 80 random bits, Crockford base32, sortable by creation. */
public final class Ids {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ids() {
    }

    public static String newId(long epochMillis) {
        char[] out = new char[26];
        long time = epochMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 0x1F)];
            time >>>= 5;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 10; i < 26; i++) {
            out[i] = ALPHABET[random.nextInt(32)];
        }
        return new String(out);
    }
}
