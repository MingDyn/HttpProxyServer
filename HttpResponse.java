package com.ming;

import java.util.HashMap;
import java.util.Map;

public class HttpResponse extends HttpBase {

    /**
     * HTTP response header fields
     */
    public static final String OK = HTTP_VERSION + " 200 OK";
    public static final String DATE = "Date";
    public static final String SERVER = "Server";
    public static final String SET_COOKIE = "Set-Cookie";
    public static final String ACCEPT_RANGES = "Accept-Ranges";
    public static final String VARY = "Vary";
    public static final String CONTENT_ENCODING = "Content-Encoding";
    public static final String CONTENT_LENGTH = "Content-Length";
    public static final String CONTENT_TYPE = "Content-Type";

    public Map<String, String> headerMap;
    public int hdrSize;
    public int contentLen; // assume within the range of int

    public HttpResponse() {
        this.headerMap = new HashMap<>();
    }

    public HttpResponse parseResponse(String response) {
        hdrSize = response.length();

        // parse hdr
        String[] strs = response.split(NEW_LINE_SEPARATOR);
        for (String line: strs) {
            if (line.startsWith(DATE)) {
                headerMap.put(DATE, line.split(" ")[1]);
            } else if (line.startsWith(SERVER)) {
                headerMap.put(SERVER, PROXY_VERSION); // proxy version
            } else if (line.startsWith(ACCEPT_RANGES)) {
                headerMap.put(ACCEPT_RANGES, line.split(" ")[1]);
            } else if (line.startsWith(CONTENT_LENGTH)) {
                String lenStr = line.split(" ")[1];
                contentLen = Integer.valueOf(lenStr);
                headerMap.put(CONTENT_LENGTH, lenStr);
            } else if (line.startsWith(CONTENT_TYPE)) {
                headerMap.put(CONTENT_TYPE, line.split(" ")[1]);
            } else if (line.startsWith(CONNECTION)) {
                headerMap.put(CONNECTION, line.split(" ")[1]);
            } else {
                System.out.println("The following response header field is not implemented yet:");
                System.out.println(line);
                System.out.println();
            }
        }

        return this;
    }
}
