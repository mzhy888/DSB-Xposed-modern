package com.dsb.xp;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/** 迪速帮骑手端接口的最小封装，请求头与宿主一致，token 由 GrabPanel 从宿主读取。 */
final class Http {
    final String base = "https://api.disubang.com/";
    final String appVersion = "1.8.6";
    volatile String token = "";
    volatile String tokenType = "Bearer";
    volatile String uid = "";
    volatile String refreshToken = "";

    private static String enc(String s) {
        if (s == null) s = "";
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String buildQuery(Map<String, String> m) {
        if (m == null || m.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return sb.toString();
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<String, String>();
        h.put("Accept", "application/json");
        h.put("app-platform", "android");
        h.put("app-version", appVersion);
        h.put("app-source", "rider");
        String t = token;
        if (t != null && t.length() > 0) {
            h.put("Authorization", tokenType + " " + t);
            String u = uid;
            if (u != null && u.length() > 0) h.put("app-uid", u);
        }
        return h;
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static JSONObject fail(String message) {
        JSONObject j = new JSONObject();
        try {
            j.put("status", "-1");
            j.put("message", message);
        } catch (Exception ignored) {
        }
        return j;
    }

    private JSONObject request(String method, String path, Map<String, String> body, Map<String, String> query) {
        HttpURLConnection c = null;
        try {
            String url = base + (path.startsWith("/") ? path.substring(1) : path);
            String q = buildQuery(query);
            if (q.length() > 0) url = url + (url.contains("?") ? "&" : "?") + q;

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod(method);
            c.setRequestProperty("Connection", "keep-alive");
            for (Map.Entry<String, String> e : headers().entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                byte[] data = buildQuery(body).getBytes("UTF-8");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream os = c.getOutputStream();
                os.write(data);
                os.flush();
                os.close();
            }
            int code = c.getResponseCode();
            String raw = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            try {
                return new JSONObject(raw);
            } catch (Exception notJson) {
                return fail(raw.length() > 200 ? raw.substring(0, 200) : raw);
            }
        } catch (Exception e) {
            return fail(String.valueOf(e));
        } finally {
            if (c != null) c.disconnect();
        }
    }

    JSONObject get(String path, Map<String, String> query) {
        return request("GET", path, null, query);
    }

    JSONObject post(String path, Map<String, String> body, Map<String, String> query) {
        return request("POST", path, body, query);
    }

    /** 楼栋列表 */
    JSONObject dorms() {
        return get("delivery/FloatList", null);
    }

    /** 拉单：pids 是逗号分隔的楼栋 id */
    JSONObject orders(String pids, String attrType, int orderType, int releaseBuffet, int groupId, int page, int pageSize) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("page_size", String.valueOf(pageSize));
        body.put("release_buffet", String.valueOf(releaseBuffet));
        if (orderType >= 0) body.put("order_type", String.valueOf(orderType));
        if (attrType != null && attrType.length() > 0) body.put("attr_type", attrType);
        if (groupId > 0) body.put("group_id", String.valueOf(groupId));
        if (pids != null && pids.length() > 0) {
            String[] arr = pids.split(",");
            for (int i = 0; i < arr.length; i++) {
                String p = arr[i].trim();
                if (p.length() > 0) body.put("delivery_pid[" + i + "]", p);
            }
        }
        Map<String, String> query = new HashMap<String, String>();
        query.put("page", String.valueOf(page));
        return post("delivery/GetTmpOrderList", body, query);
    }

    /** 抢单 */
    JSONObject scramble(int orderId, String phases) {
        Map<String, String> body = new HashMap<String, String>();
        body.put("order_id", String.valueOf(orderId));
        body.put("phases", phases);
        return post("delivery/scramble_order", body, null);
    }

    /** 用 refresh_token 换新 token，成功时更新本对象里的三个字段 */
    JSONObject refresh() {
        Map<String, String> body = new HashMap<String, String>();
        body.put("refresh_token", refreshToken);
        JSONObject r = post("refresh_token", body, null);
        if ("200".equals(r.optString("status"))) {
            JSONObject d = r.optJSONObject("data");
            if (d != null) {
                token = d.optString("access_token", token);
                tokenType = d.optString("token_type", tokenType);
                refreshToken = d.optString("refresh_token", refreshToken);
            }
        }
        return r;
    }
}
