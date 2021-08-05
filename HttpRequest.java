package com.ming;

import java.util.HashMap;
import java.util.Map;

class HttpRequest extends HttpBase {
    /**
     * HTTP request types
     */
    public static final int NOT_IMPLEMENTED = 0;
    public static final int GET_TYPE = 1;
    public static final int CONNECT_TYPE = 2;

    /**
     * HTTP request header fields
     */
    public static final String GET = "GET";
    public static final String CONNECT = "CONNECT";
    public static final String HOST = "Host";
    public static final String ACCEPT_ENCODING = "Accept-Encoding";
    public static final String ACCEPT = "Accept";
    public static final String USER_AGENT = "User-Agent";
    public static final String REFERER = "Referer";

    public Map<String, String> headerMap;
    public String body;
    public int type;
    public int port;
    public String requestURI;

    public HttpRequest() {
        this.headerMap = new HashMap<>();
    }

    // assume the request is always valid
    // TODO char based String is not optimized (conversion between byte and char)
    public HttpRequest parseRequest(String request) {
        String[] strs = request.split(HDR_BODY_SEPARATOR);
        if (strs == null || strs.length == 0 || strs.length > 2)
            return null;

        // get body or default as a new line separator
        body = strs.length > 1 ? strs[1] : NEW_LINE_SEPARATOR;

        // parse hdr
        strs = strs[0].split(NEW_LINE_SEPARATOR);
        String tmpURL = null;
        for (String line: strs) {
            if (line.startsWith(GET)) {
                type = GET_TYPE;

                tmpURL = line.split(" ")[1];
                Integer tmpPort = parsePort(tmpURL);
                port = tmpPort == null ? 80 : tmpPort;
            } else if (line.startsWith(CONNECT)) {
                type = CONNECT_TYPE;

                Integer tmpPort = parsePort(line.split(" ")[1]);
                port = tmpPort == null ? 443 : tmpPort;
            } else if (line.startsWith(HOST)) {
                String host = line.split(" ")[1].split(":")[0];
                headerMap.put(HOST, host);
            } else if (line.startsWith(CONNECTION)) {
                headerMap.put(CONNECTION, line.split(" ")[1]);
            } else if (line.startsWith(ACCEPT_ENCODING)) {
                int index = line.indexOf(' ');
                headerMap.put(ACCEPT_ENCODING, line.substring(index + 1));
            } else if (line.startsWith(ACCEPT)) {
                int index = line.indexOf(' ');
                headerMap.put(ACCEPT, line.substring(index + 1));
            } else {
                System.out.println("The following request header field is not implemented yet:");
                System.out.println(line);
                System.out.println();
            }
        }

        if (tmpURL != null)
            parseRequestedURI(tmpURL);

        return this;
    }

    public String buildGetRequest() {
        if (type == GET_TYPE) {
            StringBuilder sb = new StringBuilder();

            sb.append(GET).append(" ").append(requestURI).append(" ").append(HTTP_VERSION).append(NEW_LINE_SEPARATOR);
            sb.append(USER_AGENT).append(": ").append(PROXY_VERSION).append(NEW_LINE_SEPARATOR);

            if (headerMap.containsKey(ACCEPT))
                sb.append(ACCEPT).append(": ").append(headerMap.get(ACCEPT)).append(NEW_LINE_SEPARATOR);

            sb.append(HOST).append(": ").append(headerMap.get(HOST)).append(NEW_LINE_SEPARATOR);

            if (headerMap.containsKey(ACCEPT_ENCODING))
                sb.append(ACCEPT_ENCODING).append(": ").append(headerMap.get(ACCEPT_ENCODING)).append(NEW_LINE_SEPARATOR);

            sb.append(CONNECTION).append(": ").append(KEEP_ALIVE).append(NEW_LINE_SEPARATOR);

            sb.append(NEW_LINE_SEPARATOR);

            return sb.toString();
        }

        return null;
    }

    private void parseRequestedURI(String url) {
        // get rid of http:// or https://
        String[] strs = url.split("//");
        url = strs[strs.length - 1];

        // find the first / and get the requested uri
        int index =  url.indexOf('/');
        if (index == -1) {
            requestURI = "/";
        } else {
            requestURI = url.substring(index);
        }
    }

    private Integer parsePort(String url) {
        String[] strs = url.split("://");
        url = strs[strs.length - 1];

        strs = url.split(":");
        if (strs.length > 1) {
            return Integer.valueOf(strs[1]);
        } else {
            return null;
        }
    }
}
