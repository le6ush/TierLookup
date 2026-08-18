package com.tierlookup.net;

import com.tierlookup.client.BootstrapLog;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;


/** Small HTTP wrapper with an HttpRequest timeout plus an outer CompletableFuture watchdog. */
public final class Http {
    private static final HttpClient CLIENT=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NORMAL).build();
    /** Keep bounded response decoding off HttpClient/common-pool completion threads. */
    private static final ExecutorService BODY_EXECUTOR=Executors.newFixedThreadPool(4, r-> {
        Thread t=new Thread(r, "TierLookup-HTTP-Body"); t.setDaemon(true); t.setPriority(Thread.MIN_PRIORITY); return t;
    }
    );
    private static final int MAX_RESPONSE_BYTES=32*1024*1024;
    private static final String USER_AGENT = "TierLookup/1.0.0 Minecraft-Fabric-Client";
    private Http() {
    }
    public record Response(int statusCode, String body, String contentType, String url) {
        public boolean ok() {
            return statusCode>=200&&statusCode<300;
        }
    }
    public static CompletableFuture<String> get(String url) {
        return get(url, 4);
    }
    public static CompletableFuture<String> get(String url, int timeoutSeconds) {
        return send("GET", url, null, null, timeoutSeconds, Map.of());
    }
    public static CompletableFuture<String> get(String url, int timeoutSeconds, Map<String, String> headers) {
        return send("GET", url, null, null, timeoutSeconds, headers);
    }
    public static CompletableFuture<String> postJson(String url, String json, int timeoutSeconds) {
        return send("POST", url, json, "application/json", timeoutSeconds, Map.of());
    }
    public static CompletableFuture<String> postForm(String url, String form, int timeoutSeconds) {
        return send("POST", url, form, "application/x-www-form-urlencoded", timeoutSeconds, Map.of());
    }
    /** Raw response variant for adapters that need to distinguish semantic HTTP statuses without stack-trace spam. */
    public static CompletableFuture<Response> getResponse(String url, int timeoutSeconds) {
        return sendResponse("GET", url, null, null, timeoutSeconds, false, Map.of());
    }
    public static CompletableFuture<Response> getResponse(String url, int timeoutSeconds, Map<String, String> headers) {
        return sendResponse("GET", url, null, null, timeoutSeconds, false, headers);
    }
    private static CompletableFuture<String> send(String method, String url, String body, String contentType, int timeoutSeconds, Map<String, String> headers) {
        return sendResponse(method, url, body, contentType, timeoutSeconds, true, headers).thenApply(r-> {
            if(!r.ok())throw new HttpException(r.statusCode(), r.url(), safeSnippet(r.body())); return r.body();
        }
        );
    }
    private static CompletableFuture<Response> sendResponse(String method,
        String url,
        String body,
        String contentType,
        int timeoutSeconds,
        boolean noisyErrors,
        Map<String,
        String> headers) {
        final URI uri;
        try {
            uri=URI.create(url);
            String scheme=uri.getScheme();
            if(scheme==null||!(scheme.equalsIgnoreCase("https")||scheme.equalsIgnoreCase("http")))throw new IllegalArgumentException("unsupported URI scheme");
            if(uri.getHost()==null||uri.getHost().isBlank())throw new IllegalArgumentException("URI host missing");
        } catch (Throwable bad) {
            BootstrapLog.error("HTTP invalid URI "+safeLogUrl(url), bad);
            return CompletableFuture.failedFuture(bad);
        }
        long started=System.nanoTime();
        int requestTimeout=Math.max(1, Math.min(30, timeoutSeconds));
        
        final HttpRequest req;
        try {
            HttpRequest.Builder b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(requestTimeout)).header("Accept",
                "application/json,text/plain,*/*").header("User-Agent",
                USER_AGENT);
            if(headers!=null)for(var e:headers.entrySet()) {
                String k=e.getKey(), v=e.getValue();
                if(k==null||v==null||k.isBlank())continue;
                b.setHeader(k, v);
            }
            if(body==null)b.GET();
            else {
                if(contentType!=null)b.header("Content-Type", contentType);
                b.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            req=b.build();
        } catch (Throwable invalid) {
            return CompletableFuture.failedFuture(invalid);
        }
        CompletableFuture<HttpResponse<InputStream>> raw=CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<HttpResponse<InputStream>> guarded=raw.copy().orTimeout(requestTimeout+2L, TimeUnit.SECONDS);
        return guarded.thenApplyAsync(r-> {
            String ct=r.headers().firstValue("content-type").orElse("?");
            long declared=r.headers().firstValueAsLong("content-length").orElse(-1L);
            if(declared>MAX_RESPONSE_BYTES)throw new CompletionException(new IOException("HTTP response Content-Length exceeds "+MAX_RESPONSE_BYTES+" bytes"));
            String resp=readLimited(r.body(), charsetFor(ct), MAX_RESPONSE_BYTES);
            long ms=(System.nanoTime()-started)/1_000_000L;
            String suffix=r.statusCode()>=400?" body="+safeSnippet(resp):"";
            
            return new Response(r.statusCode(), resp, ct, url);
        },BODY_EXECUTOR).whenComplete((r, e)-> {
            if(e!=null&&!raw.isDone())raw.cancel(true); if(e!=null&&noisyErrors)BootstrapLog.error("HTTP "+method+" "+safeLogUrl(url), unwrap(e));
        }
        );
    }
    private static Charset charsetFor(String contentType) {
        if(contentType!=null) {
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*['\"]?([^;,'\" ]+)").matcher(contentType);
            if(m.find())try {
                return Charset.forName(m.group(1).trim());
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }
    private static String readLimited(InputStream input, Charset charset, int maxBytes) {
        if(input==null)return "";
        try(InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(8192, maxBytes))) {
            byte[] buf=new byte[8192];
            int total=0, n;
            while((n=in.read(buf))>=0) {
                if(n==0)continue;
                total+=n;
                if(total>maxBytes)throw new CompletionException(new IOException("HTTP response exceeds "+maxBytes+" bytes"));
                out.write(buf, 0, n);
            }
            return out.toString(charset);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }
    public static HttpException findHttpException(Throwable t) {
        Throwable x=t;
        while(x!=null) {
            if(x instanceof HttpException h)return h;
            x=x.getCause();
        }
        return null;
    }
    public static String rootMessage(Throwable t) {
        Throwable x=unwrap(t);
        String m=x==null?null:x.getMessage();
        return m==null?(x==null?"unknown error":x.getClass().getSimpleName()):m;
    }
    private static Throwable unwrap(Throwable t) {
        Throwable x=t;
        while(x!=null&&x.getCause()!=null&&(x instanceof CompletionException||x instanceof ExecutionException))x=x.getCause();
        return x==null?t:x;
    }
    public static String safeLogUrl(String url) {
        if(url==null)return "<null>";
        return url.replaceAll("(?i)(api[_-]?key|token|access[_-]?token|key)=([^&\\s]+)", "$1=<redacted>");
    }
    private static String safeSnippet(String body) {
        if(body==null)return "<null>";
        String s=body.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        while(s.contains("  "))s=s.replace("  ", " ");
        s=s.replaceAll("(?i)(\"?(?:api[_-]?key|token|access[_-]?token|password|secret)\"?\\s*[:=]\\s*\"?)[^\",;& ]+", "$1<redacted>");
        if(s.length()>160)s=s.substring(0, 160)+"…";
        return s;
    }
    public static final class HttpException extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final int statusCode;
        private final String url, bodySnippet;
        public HttpException(int statusCode, String url, String bodySnippet) {
            super("HTTP "+statusCode+(bodySnippet==null||bodySnippet.isBlank()?"":" — "+bodySnippet));
            this.statusCode=statusCode;
            this.url=url;
            this.bodySnippet=bodySnippet;
        }
        public int statusCode() {
            return statusCode;
        }
        public String url() {
            return url;
        }
        public String bodySnippet() {
            return bodySnippet;
        }
    }
}
