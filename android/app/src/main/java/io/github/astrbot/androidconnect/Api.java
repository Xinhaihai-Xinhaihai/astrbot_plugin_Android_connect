package io.github.astrbot.androidconnect;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class Api {
    private final Prefs prefs;
    Api(Prefs prefs) { this.prefs = prefs; }
    static String validateUrl(String input) throws Exception {
        URI uri = new URI(input.trim());
        String scheme = uri.getScheme(); String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new Exception("请输入完整的服务器地址，不包含路径、密钥或查询参数");
        if (!"https".equals(scheme) && !"http".equals(scheme)) throw new Exception("地址必须以 http:// 或 https:// 开头");
        if ("http".equals(scheme) && !privateIp(host))
            throw new Exception("HTTP 仅允许局域网数字 IP；外网及 Tunnel 必须使用 HTTPS");
        return new URI(scheme, null, host, uri.getPort(), null, null, null).toString();
    }
    private static boolean privateIp(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return false;
        int[] ip = new int[4];
        try { for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(parts[i]); if (ip[i] < 0 || ip[i] > 255) return false; } }
        catch (Exception e) { return false; }
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168)
            || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31);
    }
    JSONObject call(String method, String path, JSONObject body) throws Exception {
        String base = validateUrl(prefs.url());
        String token = prefs.token();
        if (token.length() < 32) throw new Exception("请先在设置页填写连接密钥");
        return request(base, token, method, path, body);
    }
    JSONObject pair(String address, String code) throws Exception {
        return request(validateUrl(address), "", "POST", "/pair", json("code", code));
    }
    private JSONObject request(String base, String token, String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(base + "/api/v1" + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000); connection.setReadTimeout(16000);
        connection.setRequestMethod(method); if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var stream = connection.getOutputStream()) { stream.write(bytes); }
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (input == null) throw new Exception("连接失败，HTTP " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (input) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 4 * 1024 * 1024) throw new Exception("服务器响应过大");
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject result;
            try { result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())); }
            catch (Exception e) { throw new Exception("服务器未返回插件数据（HTTP " + code + "），请检查地址或 Tunnel"); }
            if (code < 200 || code >= 300) throw new Exception(result.optString("error", "连接失败 HTTP " + code));
            return result;
        } finally { connection.disconnect(); }
    }
    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception e) { throw new IllegalArgumentException(e); }
        return object;
    }
}
