package io.github.astrbot.androidconnect;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Prefs {
    private static final String KEY = "astrbot-connect-token";
    private final SharedPreferences p;
    Prefs(Context context) { p = context.getSharedPreferences("connect", Context.MODE_PRIVATE); }
    String get(String key, String fallback) { return p.getString(key, fallback); }
    void put(String key, String value) { p.edit().putString(key, value).commit(); }
    String id(String key) {
        String value = get(key, "");
        if (value.isEmpty()) { value = UUID.randomUUID().toString(); put(key, value); }
        return value;
    }
    void newSession() {
        p.edit().putString("session",UUID.randomUUID().toString())
            .putString("session_started",Long.toString(System.currentTimeMillis())).commit();
    }
    String url() { return get("url", ""); }
    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(KEY)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) ks.getKey(KEY, null);
    }
    void token(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String encrypted = Base64.encodeToString(cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        p.edit().putString("token", encrypted).putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit();
    }
    String token() throws Exception {
        if (get("token", "").isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(get("iv", ""), Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(get("token", ""), Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    boolean consume(String command) {
        String seen = get("consumed", "");
        if (seen.contains("|" + command + "|")) return false;
        if (seen.length() > 12000) seen = seen.substring(seen.length() - 8000);
        return p.edit().putString("consumed", seen + "|" + command + "|").commit();
    }
}
