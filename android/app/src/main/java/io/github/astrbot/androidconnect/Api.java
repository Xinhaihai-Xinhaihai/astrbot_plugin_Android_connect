package io.github.astrbot.androidconnect;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class Api {
    private final Prefs prefs;
    Api(Prefs prefs) { this.prefs = prefs; }
    static String validateUrl(String input) throws Exception {
        return validateUrl(input, false);
    }
    static String validateUrl(String input, boolean allowPublicHttp) throws Exception {
        URI uri = new URI(input.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/")))
            throw new Exception("请输入完整的服务器地址，不包含路径、密钥或查询参数");
        if (!scheme.equals("https") && !scheme.equals("http")) throw new Exception("地址必须以 http:// 或 https:// 开头");
        if (scheme.equals("http")) {
            if (!isIpv4(host)) throw new Exception("HTTP 只允许数字 IPv4；域名请使用 HTTPS");
            if (!isLocalIpv4(host) && !allowPublicHttp)
                throw new Exception("公网 HTTP 默认关闭，请显式允许公网 IPv4 或改用 HTTPS");
        }
        return new URI(scheme, null, host, uri.getPort(), null, null, null).toString();
    }
    static boolean isPublicHttp(String normalized) {
        try {
            URI uri = new URI(normalized);
            return "http".equalsIgnoreCase(uri.getScheme()) && isIpv4(uri.getHost()) && !isLocalIpv4(uri.getHost());
        } catch (Exception ignored) { return false; }
    }
    private static int[] ipv4(String host) {
        if (host == null) return null;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] ip = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3 || !parts[i].matches("\\d+")) return null;
                ip[i] = Integer.parseInt(parts[i]);
                if (ip[i] < 0 || ip[i] > 255) return null;
            }
        } catch (Exception e) { return null; }
        return ip;
    }
    private static boolean isIpv4(String host) { return ipv4(host) != null; }
    private static boolean isLocalIpv4(String host) {
        int[] ip = ipv4(host);
        if (ip == null) return false;
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168)
            || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
            || (ip[0] == 169 && ip[1] == 254)
            || (ip[0] == 100 && ip[1] >= 64 && ip[1] <= 127);
    }
    JSONObject call(String method, String path, JSONObject body) throws Exception {
        String base = validateUrl(prefs.url(), prefs.allowPublicHttp());
        String token = prefs.token();
        if (token.length() < 32) throw new Exception("请先在设置页填写连接密钥");
        return request(base, token, method, path, body);
    }
    JSONObject pair(String address, String code) throws Exception {
        return pair(address, code, prefs.allowPublicHttp());
    }
    JSONObject pair(String address, String code, boolean allowPublicHttp) throws Exception {
        return request(validateUrl(address, allowPublicHttp), "", "POST", "/pair", json("code", code));
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
            String response=bytes.toString(StandardCharsets.UTF_8.name());
            if(code==530)throw new Exception(response.contains("1033")
                ?"Cloudflare 530 / 1033：电脑隧道未连接。请启动电脑上的 cloudflared，确认电脑未休眠；这不是手机密钥错误。"
                :"Cloudflare 530：请检查域名 DNS、隧道状态以及 Cloudflare 错误详情。");
            if(code==502)throw new Exception("Cloudflare / 服务器 502：隧道或上游无法连接。请确认 AstrBot 和插件已启动，隧道服务地址为 http://127.0.0.1:8765。");
            JSONObject result;
            try { result = new JSONObject(response); }
            catch (Exception e) { throw new Exception("服务器未返回插件数据（HTTP " + code + "），请检查地址或 Tunnel"); }
            if (code < 200 || code >= 300) throw new Exception(result.optString("error", "连接失败 HTTP " + code));
            return result;
        } finally { connection.disconnect(); }
    }
    static JSONObject json(Object... values) {
        JSONObject value = new JSONObject();
        try { for (int i=0;i<values.length;i+=2) value.put(String.valueOf(values[i]),values[i+1]); }
        catch(Exception e) { throw new IllegalStateException(e); }
        return value;
    }
}
