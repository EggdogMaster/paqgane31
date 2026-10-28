package com.paq.paqgane.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.paq.paqgane.PaqganeConfig;
import com.paq.paqgane.PaqganeMod;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Talks to an OpenAI-compatible chat endpoint (Groq by default).
 *
 * Every request goes through one queue and one worker thread, so calls are spaced out,
 * rate limits are waited out or sent to the fallback model, and reasoning models that
 * run out of tokens are retried automatically.
 */
public class GroqClient {

    public enum Priority { HIGH, LOW }

    public static class RateLimitedException extends RuntimeException {
        public RateLimitedException() {
            super("rate limited");
        }
    }

    public static class BusyException extends RuntimeException {
        public BusyException() {
            super("queue full or request waited too long");
        }
    }

    public static class EmptyReplyException extends RuntimeException {
        public EmptyReplyException(String why) {
            super("model returned an empty reply (" + why + ")");
        }
    }

    private static class HttpFailure extends RuntimeException {
        final int code;
        final String body;
        final long retryAfterMs;

        HttpFailure(int code, String body, long retryAfterMs) {
            super("HTTP " + code);
            this.code = code;
            this.body = body == null ? "" : body;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private static class Req {
        final JsonArray messages;
        final Priority prio;
        final CompletableFuture<String> future = new CompletableFuture<>();
        final long createdMs = System.currentTimeMillis();
        final long seq;

        Req(JsonArray messages, Priority prio, long seq) {
            this.messages = messages;
            this.prio = prio;
            this.seq = seq;
        }
    }

    private static class Opts {
        boolean json = true;
        String effort;
        int maxTokens;
    }

    private static final AtomicLong SEQ = new AtomicLong();
    private static final PriorityBlockingQueue<Req> QUEUE = new PriorityBlockingQueue<>(16,
            Comparator.<Req>comparingInt(r -> r.prio.ordinal()).thenComparingLong(r -> r.seq));
    private static Thread worker;
    private static long lastCallStartMs = 0;

    private static volatile long lastOkMs = 0;
    private static volatile long lastErrMs = 0;
    private static volatile String lastErr = "";
    private static volatile String lastModelUsed = "";
    private static final AtomicLong TOTAL_OK = new AtomicLong();
    private static final AtomicLong TOTAL_FAIL = new AtomicLong();

    /** Normal (player facing) request. */
    public static CompletableFuture<String> chat(JsonArray messages) {
        return chat(messages, Priority.HIGH);
    }

    public static CompletableFuture<String> chat(JsonArray messages, Priority prio) {
        startWorker();
        Req r = new Req(messages, prio, SEQ.incrementAndGet());
        int max = PaqganeConfig.MAX_QUEUE.get();
        if (QUEUE.size() >= max) {
            // make room by dropping the newest low priority request, otherwise refuse
            Req drop = null;
            for (Req q : QUEUE) {
                if (q.prio == Priority.LOW && (drop == null || q.seq > drop.seq)) {
                    drop = q;
                }
            }
            if (prio == Priority.HIGH && drop != null && QUEUE.remove(drop)) {
                drop.future.completeExceptionally(new BusyException());
            } else {
                r.future.completeExceptionally(new BusyException());
                return r.future;
            }
        }
        if (prio == Priority.LOW && QUEUE.size() >= 3) {
            r.future.completeExceptionally(new BusyException());
            return r.future;
        }
        QUEUE.add(r);
        return r.future;
    }

    public static int queueDepth() {
        return QUEUE.size();
    }

    public static String statusLine() {
        long now = System.currentTimeMillis();
        String ok = lastOkMs == 0 ? "never" : ((now - lastOkMs) / 1000) + "s ago";
        String err = lastErrMs == 0 ? "none" : ((now - lastErrMs) / 1000) + "s ago: " + lastErr;
        return "last success: " + ok + " (model " + (lastModelUsed.isEmpty() ? "-" : lastModelUsed) + ")"
                + ", last error: " + err
                + ", calls ok/failed: " + TOTAL_OK.get() + "/" + TOTAL_FAIL.get()
                + ", queued: " + QUEUE.size();
    }

    public static boolean isConfigured() {
        return !PaqganeConfig.API_KEY.get().isBlank();
    }

    private static synchronized void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(GroqClient::loop, "paqgane-ai");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loop() {
        while (true) {
            Req r;
            try {
                r = QUEUE.take();
            } catch (InterruptedException e) {
                return;
            }
            if (r.future.isDone()) {
                continue;
            }
            long maxWait = r.prio == Priority.HIGH ? 40_000L : 20_000L;
            if (System.currentTimeMillis() - r.createdMs > maxWait) {
                r.future.completeExceptionally(new BusyException());
                continue;
            }
            try {
                pace();
                String out = execute(r);
                lastOkMs = System.currentTimeMillis();
                TOTAL_OK.incrementAndGet();
                r.future.complete(out);
            } catch (Throwable t) {
                lastErrMs = System.currentTimeMillis();
                lastErr = t.getMessage() == null ? t.toString() : t.getMessage();
                if (lastErr.length() > 300) {
                    lastErr = lastErr.substring(0, 300);
                }
                TOTAL_FAIL.incrementAndGet();
                r.future.completeExceptionally(t);
            }
        }
    }

    private static void pace() {
        int rpm = PaqganeConfig.REQUESTS_PER_MINUTE.get();
        if (rpm > 0) {
            long gap = 60_000L / rpm;
            long wait = lastCallStartMs + gap - System.currentTimeMillis();
            if (wait > 0) {
                sleep(wait);
            }
        }
        lastCallStartMs = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String execute(Req r) {
        String model = PaqganeConfig.MODEL.get();
        String fallback = PaqganeConfig.FALLBACK_MODEL.get().trim();
        Opts o = new Opts();
        o.maxTokens = PaqganeConfig.MAX_TOKENS.get();
        String eff = PaqganeConfig.REASONING_EFFORT.get().trim();
        o.effort = eff.isEmpty() ? null : eff;

        int jsonFailures = 0;
        int rateRetries = 0;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                String out = call(r, model, o);
                lastModelUsed = model;
                return out;
            } catch (HttpFailure f) {
                String b = f.body.toLowerCase();
                if (f.code == 429) {
                    long wait = f.retryAfterMs > 0 ? f.retryAfterMs : 6000L;
                    if (wait <= 20_000L && rateRetries < 2) {
                        rateRetries++;
                        sleep(wait + 250);
                        continue;
                    }
                    if (!fallback.isEmpty() && !model.equals(fallback)) {
                        PaqganeMod.LOGGER.info("Paqgane: {} is rate limited, trying fallback {}", model, fallback);
                        model = fallback;
                        continue;
                    }
                    throw new RateLimitedException();
                }
                if (f.code == 400 && o.effort != null && b.contains("reasoning_effort")) {
                    o.effort = null;
                    continue;
                }
                if (f.code == 400 && o.json && (b.contains("json_validate_failed") || b.contains("response_format") || b.contains("json mode"))) {
                    jsonFailures++;
                    if (jsonFailures == 1) {
                        // most common cause: a reasoning model spent every token thinking
                        o.maxTokens = Math.min(o.maxTokens * 2, 8000);
                    } else {
                        o.json = false;
                    }
                    continue;
                }
                if ((f.code == 404 || f.code == 403 || f.code >= 500) && !fallback.isEmpty() && !model.equals(fallback)) {
                    PaqganeMod.LOGGER.info("Paqgane: {} failed with HTTP {}, trying fallback {}", model, f.code, fallback);
                    model = fallback;
                    continue;
                }
                String body = f.body.length() > 400 ? f.body.substring(0, 400) : f.body;
                throw new RuntimeException("HTTP " + f.code + ": " + body);
            } catch (EmptyReplyException e) {
                if (o.maxTokens < 6000) {
                    o.maxTokens = Math.min(o.maxTokens * 2, 8000);
                    if (o.effort == null) {
                        o.effort = "low";
                    }
                    continue;
                }
                if (!fallback.isEmpty() && !model.equals(fallback)) {
                    model = fallback;
                    continue;
                }
                throw e;
            }
        }
        throw new RuntimeException("gave up after several retries");
    }

    private static String call(Req r, String model, Opts o) {
        String key = PaqganeConfig.API_KEY.get();
        String url = PaqganeConfig.API_URL.get();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.add("messages", r.messages);
            body.addProperty("temperature", 0.8);
            body.addProperty("max_tokens", o.maxTokens);
            if (o.json) {
                JsonObject fmt = new JsonObject();
                fmt.addProperty("type", "json_object");
                body.add("response_format", fmt);
            }
            if (o.effort != null && !o.effort.isBlank()) {
                body.addProperty("reasoning_effort", o.effort);
            }

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(8000);
            c.setReadTimeout(45000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String text = is == null ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            if (code < 200 || code >= 300) {
                long retryMs = 0;
                String ra = c.getHeaderField("retry-after");
                if (ra != null) {
                    try {
                        retryMs = (long) (Double.parseDouble(ra.trim()) * 1000.0);
                    } catch (NumberFormatException ignored) {
                        // leave 0
                    }
                }
                c.disconnect();
                throw new HttpFailure(code, text, retryMs);
            }
            c.disconnect();

            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                throw new EmptyReplyException("no choices");
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            String content = "";
            if (message != null && message.has("content")) {
                JsonElement ce = message.get("content");
                if (ce != null && !ce.isJsonNull()) {
                    content = ce.getAsString();
                }
            }
            if (content.isBlank()) {
                JsonElement fr = choice.get("finish_reason");
                throw new EmptyReplyException(fr == null || fr.isJsonNull() ? "no finish reason" : fr.getAsString());
            }
            return content;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Plain helper used by commands: builds a one message request. */
    public static JsonArray single(String system, String user) {
        JsonArray a = new JsonArray();
        JsonObject s = new JsonObject();
        s.addProperty("role", "system");
        s.addProperty("content", system);
        a.add(s);
        JsonObject u = new JsonObject();
        u.addProperty("role", "user");
        u.addProperty("content", user);
        a.add(u);
        return a;
    }
}
