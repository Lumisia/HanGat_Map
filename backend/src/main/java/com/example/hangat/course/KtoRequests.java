package com.example.hangat.course;

import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.*;
import java.net.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** KTO-only bounded exchanges, including body reads; no shared HTTP client mutation. */
final class KtoRequests {
    private static final Set<Integer> RETRY = Set.of(408,429,500,502,503,504);
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "kto-exchange"); t.setDaemon(true); return t; });
    interface Sleeper { void sleep(long milliseconds) throws InterruptedException; }
    private final Duration deadline;
    private final Sleeper sleeper;
    KtoRequests(Duration deadline, Sleeper sleeper) { this.deadline = deadline; this.sleeper = sleeper; }
    static RestClient client(Duration connect, Duration read) {
        if (connect.isZero() || connect.isNegative() || read.isZero() || read.isNegative()
                || connect.compareTo(Duration.ofSeconds(30)) > 0 || read.compareTo(Duration.ofSeconds(60)) > 0)
            throw new IllegalArgumentException("KTO timeout must be positive and bounded (connect <=30s, read <=60s)");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connect); factory.setReadTimeout(read);
        return RestClient.builder().requestFactory(factory).build();
    }
    <T> T execute(int page, Supplier<T> action) {
        boolean readTimedOut = false;
        for (int attempt = 1; ; attempt++) {
            String failure; boolean retry; HttpHeaders headers = null;
            try {
                T result = bounded(action);
                LoggerFactory.getLogger(KtoRequests.class).info("KTO_REQUEST page={} attempt={} result=SUCCESS", page, attempt);
                return result;
            } catch (RestClientResponseException e) {
                failure = "HTTP_" + e.getStatusCode().value(); retry = RETRY.contains(e.getStatusCode().value()); headers = e.getResponseHeaders();
            } catch (ResourceAccessException e) {
                failure = networkType(e); retry = !failure.equals("NETWORK_OTHER");
                readTimedOut |= failure.equals("READ_TIMEOUT");
            } catch (KtoApiException e) { throw e;
            } catch (RestClientException | IllegalArgumentException e) {
                failure = "RESPONSE_CONTRACT"; retry = false;
            }
            boolean again = retry && attempt < (readTimedOut ? 2 : 3);
            LoggerFactory.getLogger(KtoRequests.class).warn("KTO_REQUEST page={} attempt={} failure={} retry={}", page, attempt, failure, again);
            if (!again) throw new KtoApiException(retry);
            try { sleeper.sleep(delay(attempt, headers, Instant.now())); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new KtoApiException(true); }
        }
    }
    private <T> T bounded(Supplier<T> action) {
        Future<T> pending;
        try { pending = IO.submit(action::get); }
        catch (RejectedExecutionException e) { throw new KtoApiException(true); }
        try { return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) {
            pending.cancel(true);
            throw new ResourceAccessException("KTO exchange deadline", new SocketTimeoutException());
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new KtoApiException(true);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new KtoApiException(false);
        }
    }
    static String networkType(Throwable e) {
        for (Throwable c=e; c!=null; c=c.getCause()) {
            if (c instanceof java.net.http.HttpConnectTimeoutException || c instanceof ConnectException) return "CONNECT";
            if (c instanceof SocketTimeoutException) return c.getMessage()!=null && c.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connect") ? "CONNECT" : "READ_TIMEOUT";
            if (c instanceof SocketException || c instanceof UnknownHostException) return "CONNECTION_FAILURE";
        }
        return "NETWORK_OTHER";
    }
    static long delay(int attempt, HttpHeaders headers, Instant now) {
        String value = headers == null ? null : headers.getFirst("Retry-After");
        if (value != null) {
            try { return Math.min(5, Math.max(0, Long.parseLong(value.trim()))) * 1000; }
            catch (NumberFormatException ignored) {
                try { return Math.min(5000, Math.max(0, Duration.between(now, ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis())); }
                catch (java.time.DateTimeException ignoredDate) { /* Use bounded backoff for malformed Retry-After. */ }
            }
        }
        return Math.min(attempt, 2) * 1000L;
    }
}
