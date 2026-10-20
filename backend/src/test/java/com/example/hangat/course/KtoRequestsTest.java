package com.example.hangat.course;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.web.client.*;
import org.springframework.test.web.client.MockRestServiceServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KtoRequestsTest {
    final List<Long> sleeps = new ArrayList<>();
    final KtoRequests policy = new KtoRequests(Duration.ofSeconds(1), sleeps::add);
    @Test void successOnce() {
        var count=new AtomicInteger();
        assertThat(policy.execute(1, () -> count.incrementAndGet())).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints={408,429,500,502,503,504})
    void transientStatusRetriesThree(int status) {
        var count=new AtomicInteger();
        assertThatThrownBy(()->policy.execute(1,()->{count.incrementAndGet();throw error(status);})).isInstanceOf(KtoApiException.class);
        assertThat(count).hasValue(3); assertThat(sleeps).containsExactly(1000L,2000L);
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,404})
    void permanentStatusNeverRetriesOrLeaks(int status) {
        var count=new AtomicInteger();
        Throwable failure=catchThrowable(()->policy.execute(1,()->{count.incrementAndGet();throw error(status);}));
        assertThat(count).hasValue(1); assertThat(sleeps).isEmpty();
        assertThat(failure).isInstanceOf(KtoApiException.class).hasMessage(KtoApiException.USER_MESSAGE).hasNoCause();
    }
    @Test void connectRetriesThree() { network(new SocketTimeoutException("connect timed out"),3); }
    @Test void connectionResetRetriesThree() { network(new SocketException("Connection reset"),3); }
    @Test void readTimeoutRetriesOnlyTwice() { network(new SocketTimeoutException("Read timed out"),2); }
    void network(java.io.IOException error,int attempts) {
        var count=new AtomicInteger();
        assertThatThrownBy(()->policy.execute(1,()->{count.incrementAndGet();throw new ResourceAccessException("private URI",error);})).isInstanceOf(KtoApiException.class);
        assertThat(count).hasValue(attempts);
    }
    @Test void retriesCanRecover() {
        var count=new AtomicInteger();
        assertThat(policy.execute(1,()->{if(count.incrementAndGet()==1) throw error(503);return "ok";})).isEqualTo("ok");
        assertThat(count).hasValue(2);
    }
    @Test void retryAfterBoundedAndDateSupported() {
        var h=new HttpHeaders(); var now=Instant.parse("2026-09-06T00:00:00Z");
        h.set("Retry-After","99"); assertThat(KtoRequests.delay(1,h,now)).isEqualTo(5000);
        h.set("Retry-After","2"); assertThat(KtoRequests.delay(1,h,now)).isEqualTo(2000);
        h.set("Retry-After","Sun, 6 Sep 2026 00:00:04 GMT"); assertThat(KtoRequests.delay(1,h,now)).isEqualTo(4000);
        h.set("Retry-After","invalid"); assertThat(KtoRequests.delay(1,h,now)).isEqualTo(1000);
        var count=new AtomicInteger(); h.set("Retry-After","99");
        assertThatThrownBy(()->policy.execute(1,()->{count.incrementAndGet();throw new RestClientResponseException("hidden",429,"",h,null,null);})).isInstanceOf(KtoApiException.class);
        assertThat(sleeps).containsExactly(5000L,5000L);
    }
    @ParameterizedTest @ValueSource(strings={"not-json", "{}", "{\"response\":{\"header\":{\"resultCode\":\"30\"}}}"})
    void malformedOrProviderErrorNeverBecomesEmptySuccess(String body) {
        var b=RestClient.builder(); var server=MockRestServiceServer.bindTo(b).build();
        server.expect(anything()).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var service=new TourApiService(b.build(),"https://test.invalid","test-only-key",policy);
        assertThatThrownBy(service::getTourPlaces).isInstanceOf(KtoApiException.class).hasMessage(KtoApiException.USER_MESSAGE).hasNoCause();
        assertThat(sleeps).isEmpty(); server.verify();
    }
    @Test void missingKeyNeverCallsProvider() {
        var b=RestClient.builder(); var server=MockRestServiceServer.bindTo(b).build();
        assertThatThrownBy(()->new TourApiService(b.build(),"https://test.invalid","",policy).getTourPlaces()).isInstanceOf(KtoApiException.class);
        server.verify(); assertThat(sleeps).isEmpty();
    }
    @Test void absoluteDeadlineStopsUnresponsiveExchange() {
        var attempts=new AtomicInteger();
        var shortPolicy=new KtoRequests(Duration.ofMillis(40),sleeps::add);
        long start=System.nanoTime();
        assertThatThrownBy(()->shortPolicy.execute(1,()->{attempts.incrementAndGet(); try {Thread.sleep(5000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return "late";})).isInstanceOf(KtoApiException.class);
        assertThat(attempts).hasValue(2);
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(2));
    }
    @Test void realSocketReadTimeoutIsApplied() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange->{try {Thread.sleep(200);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}exchange.close();});
        server.start();
        try {
            var service=new TourApiService(KtoRequests.client(Duration.ofMillis(50),Duration.ofMillis(30)),
                    "http://127.0.0.1:"+server.getAddress().getPort(),"test-only",policy);
            assertThatThrownBy(service::getTourPlaces).isInstanceOf(KtoApiException.class);
            assertThat(sleeps).containsExactly(1000L);
        } finally {server.stop(0);}
    }
    @Test void zeroTimeoutIsRejected() {
        assertThatThrownBy(()->KtoRequests.client(Duration.ZERO,Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
    }
    static RestClientResponseException error(int status) {return new RestClientResponseException("secret response URI",status,"",null,"provider secret".getBytes(),null);}
}
