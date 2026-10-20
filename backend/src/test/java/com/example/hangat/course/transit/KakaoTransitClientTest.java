package com.example.hangat.course.transit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static com.example.hangat.course.transit.TransitRouteResponse.*;

class KakaoTransitClientTest {
    static final String RESPONSE="""
        {"status":"OK","properties":{"landingURL":"https://map.kakao.com/link/by/traffic/a/b"},"routes":[{
        "properties":{"totalDistance":5013,"totalTime":2115,"transfers":1,"fare":{"value":1350}},
        "steps":[{"properties":{"type":"WALKING","distance":886,"time":600}},
        {"properties":{"type":"BUS","distance":4127,"time":1158,"vehicles":[{"name":"201"}],"stops":[{"name":"출발 정류장"},{"name":"도착 정류장"}]}}]}]}
        """;
    final ObjectMapper json=new ObjectMapper();
    final Stop from=new Stop("1","출발",33.4,126.5), to=new Stop("2","도착",33.5,126.6);
    @Test void officialShapeKeepsMetresSecondsAndDoesNotSumTotalsAndSteps()throws Exception {
        var r=KakaoTransitClient.parse(json.readTree(RESPONSE));
        assertThat(r.status()).isEqualTo("OK");assertThat(r.duration()).isEqualTo(2115);assertThat(r.distance()).isEqualTo(5013);
        assertThat(r.steps().get(0).durationSeconds()).isEqualTo(600);assertThat(r.steps().get(1).vehicles()).containsExactly("201");
        assertThat(r.steps().get(1).stops()).containsExactly("출발 정류장","도착 정류장");assertThat(r.transfers()).isEqualTo(1);
    }
    @Test void missingAndBusinessErrorsNeverBecomeZeroOrCarCodes()throws Exception {
        for(String s:new String[]{"NO_RESULTS","STARTNODES_NULL","ENDNODES_NULL","EQUAL_POINTS","INVALID_REQUEST"}) {
            var r=KakaoTransitClient.parse(json.readTree("{\"status\":\""+s+"\"}"));assertThat(r.status()).isEqualTo(s);assertThat(r.duration()).isNull();
        }
        assertThat(KakaoTransitClient.parse(json.readTree("{\"status\":\"OK\",\"routes\":[]}")).status()).isEqualTo("INVALID_RESPONSE");
    }
    @Test void transientRetryUsesRestHeaderAndExactParametersButQuotaStopsAllRemainingLegs()throws Exception {
        var calls=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/route",x->{
            assertThat(x.getRequestHeaders().getFirst("Authorization")).isEqualTo("KakaoAK test-only-key");
            assertThat(x.getRequestURI().getQuery()).contains("start_x=126.5","start_y=33.4","end_x=126.6").doesNotContain("key","departure");
            int n=calls.incrementAndGet();byte[] body=RESPONSE.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(n==1?503:n==2?200:429,body.length);x.getResponseBody().write(body);x.close();
        });server.start();
        try {
            var client=new KakaoTransitClient(json,"test-only-key",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/route"));
            var budget=new KakaoTransitClient.Budget();assertThat(client.route(from,to,budget).status()).isEqualTo("OK");assertThat(budget.attempts).isEqualTo(2);
            assertThat(client.route(from,to,budget).status()).isEqualTo("QUOTA_EXCEEDED");
            assertThat(client.route(from,to,budget).status()).isEqualTo("PROVIDER_BLOCKED");assertThat(calls).hasValue(3);
        }finally{server.stop(0);}
    }
    @Test void disabledInvalidAndExhaustedBudgetNeverCallNetwork() {
        var client=new KakaoTransitClient(json,"test-only-key",false,URI.create("http://127.0.0.1:1/route"));
        var b=new KakaoTransitClient.Budget();assertThat(client.route(from,to,b).status()).isEqualTo("NOT_ENABLED");assertThat(b.attempts).isZero();
        client=new KakaoTransitClient(json,"test-only-key",true,URI.create("http://127.0.0.1:1/route"));b.attempts=12;
        assertThat(client.route(from,to,b).status()).isEqualTo("REQUEST_LIMIT");
        assertThat(client.route(new Stop("x","x",null,null),to,b).status()).isEqualTo("INVALID_COORDINATE");
    }
    @Test void permissionAndHttp400QuotaAreNotRetried()throws Exception {
        for(int status:new int[]{401,403,400}) {
            var count=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/route",x->{count.incrementAndGet();byte[] body="{\"code\":-10}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,body.length);x.getResponseBody().write(body);x.close();});server.start();
            try {var client=new KakaoTransitClient(json,"test-only-key",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/route"));
                var b=new KakaoTransitClient.Budget();assertThat(client.route(from,to,b).status()).isEqualTo(status==400?"QUOTA_EXCEEDED":"PROVIDER_PERMISSION");
                client.route(from,to,b);assertThat(count).hasValue(1);
            }finally{server.stop(0);}
        }
    }
    @Test void slowResponseHonorsWholeRequestDeadline()throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/route",x->{try{Thread.sleep(500);}catch(InterruptedException e){Thread.currentThread().interrupt();}x.close();});server.start();
        try {var client=new KakaoTransitClient(json,"test-only-key",true,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/route"));
            var b=new KakaoTransitClient.Budget(java.time.Duration.ofMillis(100));long started=System.nanoTime();
            assertThat(client.route(from,to,b).status()).isEqualTo("REQUEST_LIMIT");
            assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(java.time.Duration.ofSeconds(2));assertThat(b.attempts).isLessThanOrEqualTo(1);
        }finally{server.stop(0);}
    }
}
