package com.example.hangat.course.transit;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import static com.example.hangat.course.transit.TransitRouteResponse.*;

/** Kakao Map publictraffic, not the Mobility car contract. No header/body logging. */
@Component
public class KakaoTransitClient {
    private final HttpClient http;
    private final ObjectMapper json;
    private final String key;
    private final URI endpoint;
    private final boolean enabled;
    private volatile long blockedUntil;
    @Autowired
    public KakaoTransitClient(ObjectMapper json, @Value("${kakao-local.rest-key:}") String key,
            @Value("${kakao-transit.enabled:true}") boolean enabled) {
        this(json, key, enabled, URI.create("https://dapi.kakao.com/v2/routing/publictraffic"));
    }
    KakaoTransitClient(ObjectMapper json, String key, boolean enabled, URI endpoint) {
        this.json=json; this.key=key; this.enabled=enabled; this.endpoint=endpoint;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    static final class Budget {
        final long deadline;
        Budget() { this(Duration.ofSeconds(26)); }
        Budget(Duration allowance) { deadline=System.nanoTime()+allowance.toNanos(); }
        int attempts; boolean stop;
    }
    record Result(String status, Long distance, Long duration, Integer transfers, String landing, List<Step> steps) {
        static Result failure(String status) { return new Result(status,null,null,null,null,List.of()); }
    }
    static String requestKey(Stop from, Stop to) {
        return "publictraffic|GET|input_coord=WGS84|output_coord=WGS84|"
                +Double.toHexString(from.longitude())+","+Double.toHexString(from.latitude())+"->"
                +Double.toHexString(to.longitude())+","+Double.toHexString(to.latitude());
    }
    Result route(Stop from, Stop to, Budget budget) {
        if (!enabled || key.isBlank()) return Result.failure("NOT_ENABLED");
        if (budget.stop || System.nanoTime() < blockedUntil) return Result.failure("PROVIDER_BLOCKED");
        if (!valid(from) || !valid(to)) return Result.failure("INVALID_COORDINATE");
        for (int retry=0; retry<2; retry++) {
            long remaining=budget.deadline-System.nanoTime();
            if (budget.attempts>=12 || remaining<=0) return Result.failure("REQUEST_LIMIT");
            budget.attempts++;
            URI uri=URI.create(endpoint+"?start_x="+from.longitude()+"&start_y="+from.latitude()
                    +"&end_x="+to.longitude()+"&end_y="+to.latitude()+"&input_coord=WGS84&output_coord=WGS84");
            try {
                var request=HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(remaining,Duration.ofSeconds(6).toNanos())))
                        .header("Authorization", "KakaoAK "+key).GET().build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                int status=response.statusCode();
                int code=0;
                if(status==400) { var error=json.readTree(response.body());code=error.path("code").asInt(0); }
                if (status==401 || status==403 || status==429 || code==-10 || code==-11 || code==-13 || code==-903) {
                    budget.stop=true; blockedUntil=System.nanoTime()+Duration.ofMinutes(1).toNanos();
                    return Result.failure(status==429 || code==-10 || code==-11 ? "QUOTA_EXCEEDED" : "PROVIDER_PERMISSION");
                }
                if (status==200) return parse(json.readTree(response.body()));
                if (!(status==502 || status==503 || status==504 || code==-7 || code==-603) || retry==1) return Result.failure("PROVIDER_ERROR");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); budget.stop=true; return Result.failure("INTERRUPTED");
            } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
                return Result.failure("INVALID_RESPONSE");
            } catch (IOException transientFailure) {
                if (retry==1) return Result.failure("NETWORK_TIMEOUT");
            }
            try { Thread.sleep(ThreadLocalRandom.current().nextLong(100,201)); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt();budget.stop=true;return Result.failure("INTERRUPTED"); }
        }
        return Result.failure("PROVIDER_ERROR");
    }
    static boolean valid(Stop p) {
        return p.latitude()!=null && p.longitude()!=null && Double.isFinite(p.latitude()) && Double.isFinite(p.longitude())
                && p.latitude()>=-90 && p.latitude()<=90 && p.longitude()>=-180 && p.longitude()<=180;
    }
    static Result parse(JsonNode root) {
        String status=root.path("status").asText();
        if (!"OK".equals(status)) return Result.failure(Set.of("STARTNODES_NULL","ENDNODES_NULL","EQUAL_POINTS","INVALID_REQUEST","NO_RESULTS").contains(status) ? status : "INVALID_RESPONSE");
        // No documented ranking flag: select the first route in provider response order.
        JsonNode route=root.path("routes").path(0), p=route.path("properties");
        Long distance=number(p,"totalDistance"), duration=number(p,"totalTime");
        if (distance==null || duration==null || !route.path("steps").isArray() || route.path("steps").isEmpty()) return Result.failure("INVALID_RESPONSE");
        List<Step> steps=new ArrayList<>();
        for(var step:route.path("steps")) {
            var s=step.path("properties");String type=s.path("type").asText();
            if (!Set.of("BUS","SUBWAY","WALKING").contains(type)) return Result.failure("INVALID_RESPONSE");
            steps.add(new Step(type,number(s,"distance"),number(s,"time"),names(s.path("stops")),names(s.path("vehicles"))));
        }
        Long transfers=number(p,"transfers");
        String landing=root.path("properties").path("landingURL").asText(null);
        if (landing!=null) try { URI u=URI.create(landing);if(!"https".equals(u.getScheme())||!"map.kakao.com".equals(u.getHost())||u.getUserInfo()!=null)landing=null; } catch(IllegalArgumentException invalid){landing=null;}
        return new Result("OK",distance,duration,transfers==null||transfers>Integer.MAX_VALUE?null:transfers.intValue(),landing,List.copyOf(steps));
    }
    private static Long number(JsonNode n,String field) { var v=n.path(field);return v.isIntegralNumber()&&v.canConvertToLong()&&v.longValue()>=0?v.longValue():null; }
    private static List<String> names(JsonNode array) { List<String> values=new ArrayList<>();if(array.isArray())for(var n:array)if(n.path("name").isTextual())values.add(n.path("name").textValue());return List.copyOf(values); }
}
