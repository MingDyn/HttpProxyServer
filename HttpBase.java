package com.ming;

import java.nio.charset.StandardCharsets;

public abstract class HttpBase {

    public static final String HDR_BODY_SEPARATOR = "\r\n\r\n";
    public static final String NEW_LINE_SEPARATOR = "\r\n";
    public static final String HTTP_VERSION = "HTTP/1.1";
    public static final String PROXY_VERSION = "MYProxy/1.0";
    public static final String CONNECTION = "Connection";
    public static final String KEEP_ALIVE = "Keep-Alive";

    public static int endOfEmptyLine(byte[] array, int end) {
        byte[] target = HDR_BODY_SEPARATOR.getBytes(StandardCharsets.UTF_8);
        int len = target.length;

        if (end < len)
            return -1;

        return indexOfIn(target, len, array, end) + len;
    }

    private static int indexOfIn(byte[] target, int len1, byte[] in, int len2) {
        int res = -1;

        for (int i = 0; i < len2; i++) {
            if (areBytesEqual(target, 0, in, i, len1))
                return i;
        }

        return res;
    }

    private static boolean areBytesEqual(byte[] arr1, int from1, byte[] arr2, int from2, int len) {
        for (int i = 0; i < len; i++) {
            if (
                    from1 + i >= arr1.length ||
                    from2 + i >= arr2.length ||
                    arr1[from1 + i] != arr2[from2 + i]
            ) {
                return false;
            }
        }

        return true;
    }
}
