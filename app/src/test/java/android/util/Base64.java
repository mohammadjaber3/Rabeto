package android.util;

/**
 * Test-source implementation of android.util.Base64.
 *
 * Gradle unit tests run against a stub android.jar whose methods throw
 * "not mocked". Protocol and Crypto legitimately need real Base64, and the usual
 * answer is Robolectric, which downloads an android-all jar from Maven on first
 * run. That makes the test suite fail on a phone with no connectivity, which for
 * an offline-first project is the wrong trade.
 *
 * A class in the test source set shadows the stub, so the entire suite runs on a
 * plain JVM with zero network. Behaviour matches the platform for the flags
 * Rabeto actually uses: NO_WRAP, URL_SAFE, DEFAULT.
 */
public final class Base64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;

    private Base64() {}

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return null;
        byte[] out = encode(input, flags);
        return new String(out, java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] encode(byte[] input, int flags) {
        if (input == null) return null;
        java.util.Base64.Encoder e = ((flags & URL_SAFE) != 0)
                ? java.util.Base64.getUrlEncoder()
                : java.util.Base64.getEncoder();
        if ((flags & NO_PADDING) != 0) e = e.withoutPadding();

        String encoded = e.encodeToString(input);
        if ((flags & NO_WRAP) == 0) {
            // The platform appends a newline unless NO_WRAP is set.
            encoded = encoded + ((flags & CRLF) != 0 ? "\r\n" : "\n");
        }
        return encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    public static byte[] decode(String str, int flags) {
        if (str == null) throw new IllegalArgumentException("null input");
        return decode(str.getBytes(java.nio.charset.StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        if (input == null) throw new IllegalArgumentException("null input");
        String s = new String(input, java.nio.charset.StandardCharsets.US_ASCII).trim();
        try {
            // The platform decoder is lenient about which alphabet it is given.
            if ((flags & URL_SAFE) != 0 || s.indexOf('-') >= 0 || s.indexOf('_') >= 0) {
                return java.util.Base64.getUrlDecoder().decode(pad(s));
            }
            return java.util.Base64.getDecoder().decode(pad(s));
        } catch (IllegalArgumentException first) {
            try {
                return java.util.Base64.getMimeDecoder().decode(pad(s));
            } catch (IllegalArgumentException second) {
                // Matches the platform contract: bad input throws.
                throw second;
            }
        }
    }

    private static String pad(String s) {
        int remainder = s.length() % 4;
        if (remainder == 0) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = remainder; i < 4; i++) sb.append('=');
        return sb.toString();
    }
}
