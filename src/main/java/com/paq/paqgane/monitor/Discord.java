package com.paq.paqgane.monitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeMod;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Discord {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "paqgane-discord");
        t.setDaemon(true);
        return t;
    });

    public static void send(String message) {
        String url = PaqganeConfig.WEBHOOK_URL.get();
        if (url == null || url.isBlank()) {
            return;
        }
        String content = message.length() > 1900 ? message.substring(0, 1900) : message;
        POOL.execute(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("username", "Paqgane");
                body.addProperty("content", content);
                JsonObject allowed = new JsonObject();
                allowed.add("parse", new JsonArray());
                body.add("allowed_mentions", allowed);

                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code >= 300) {
                    PaqganeMod.LOGGER.warn("Discord webhook returned HTTP {}", code);
                }
                c.disconnect();
            } catch (Exception e) {
                PaqganeMod.LOGGER.warn("Discord webhook failed: {}", e.toString());
            }
        });
    }
}
