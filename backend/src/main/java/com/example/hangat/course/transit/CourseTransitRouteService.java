package com.example.hangat.course.transit;
import com.example.hangat.course.service.CourseQueryService;
import com.example.hangat.course.model.CourseDetailResponse;
import com.example.hangat.course.model.enums.Transport;
import com.example.hangat.common.exception.BaseException;
import com.example.hangat.common.model.BaseResponseStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.example.hangat.course.transit.TransitRouteResponse.*;

/** DB snapshot/permission check completes before any external wait. No persistence dependency. */
@Service
public class CourseTransitRouteService {
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final int CACHE_LIMIT = 256;
    private final CourseQueryService queries;
    private final KakaoTransitClient client;
    private final Clock clock;
    private final Semaphore active = new Semaphore(2);
    private final ConcurrentHashMap<String, CompletableFuture<TransitRouteResponse>> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<KakaoTransitClient.Result>> legRunning = new ConcurrentHashMap<>();
    private final Map<String, CachedLeg> legCache = new LinkedHashMap<>();
    @Autowired
    public CourseTransitRouteService(CourseQueryService queries, KakaoTransitClient client) {this(queries,client,Clock.systemUTC());}
    CourseTransitRouteService(CourseQueryService queries, KakaoTransitClient client, Clock clock) {this.queries=queries;this.client=client;this.clock=clock;}
    public TransitRouteResponse route(Long id, Long user) {
        var course=queries.detail(id,user); // same public READY/SAMPLE and SAVED owner policy as detail GET
        if(course.transport()!=Transport.PUBLIC_TRANSIT)throw new BaseException(BaseResponseStatus.COURSE_INVALID_CONDITION);
        String key=signature(course);
        var own=new CompletableFuture<TransitRouteResponse>();
        var other=running.putIfAbsent(key,own);
        if(other!=null)try{return other.get(28,TimeUnit.SECONDS).cacheHit();}
        catch(InterruptedException e){Thread.currentThread().interrupt();return unavailable(course,"INTERRUPTED");}
        catch(ExecutionException|TimeoutException e){return unavailable(course,"REQUEST_LIMIT");}
        boolean acquired=active.tryAcquire();
        try {
            var response=calculate(course,acquired);
            own.complete(response);return response;
        } catch(RuntimeException e){own.completeExceptionally(e);throw e;}
        finally{if(acquired)active.release();running.remove(key,own);}
    }
    private TransitRouteResponse calculate(CourseDetailResponse course, boolean allowProvider) {
        var budget=new KakaoTransitClient.Budget();var cacheMisses=new int[1];var days=new ArrayList<Day>();
        for(var day:course.days()) {
            var points=points(course,day);var legs=new ArrayList<Leg>();
            for(int i=1;i<points.size();i++) {
                var from=points.get(i-1);var to=points.get(i);
                var r=routeLeg(from,to,budget,allowProvider,cacheMisses);
                legs.add(new Leg(from,to,r.status(),r.distance(),r.duration(),r.transfers(),r.landing(),r.steps()));
            }
            days.add(day(day,legs));
        }
        return new TransitRouteResponse(course.id(),clock.instant(),cacheMisses[0]==0,budget.attempts,List.copyOf(days));
    }
    private KakaoTransitClient.Result routeLeg(Stop from, Stop to, KakaoTransitClient.Budget budget, boolean allowProvider, int[] cacheMisses) {
        if(!KakaoTransitClient.valid(from)||!KakaoTransitClient.valid(to)){cacheMisses[0]++;return client.route(from,to,budget);}
        String key=KakaoTransitClient.requestKey(from,to);var hit=cachedLeg(key);
        if(hit!=null)return hit;
        cacheMisses[0]++;
        if(!allowProvider)return KakaoTransitClient.Result.failure("BUSY");
        var own=new CompletableFuture<KakaoTransitClient.Result>();var other=legRunning.putIfAbsent(key,own);
        if(other!=null)try{return other.get(13,TimeUnit.SECONDS);}
        catch(InterruptedException e){Thread.currentThread().interrupt();return KakaoTransitClient.Result.failure("INTERRUPTED");}
        catch(ExecutionException|TimeoutException e){return KakaoTransitClient.Result.failure("REQUEST_LIMIT");}
        try {
            hit=cachedLeg(key);if(hit!=null){own.complete(hit);return hit;}
            var result=client.route(from,to,budget);
            if("OK".equals(result.status()))putCachedLeg(key,result);
            own.complete(result);return result;
        } catch(RuntimeException e){own.completeExceptionally(e);throw e;}
        finally{legRunning.remove(key,own);}
    }
    private KakaoTransitClient.Result cachedLeg(String key) {
        synchronized(legCache){
            var value=legCache.get(key);
            if(value==null)return null;
            if(!value.storedAt().plus(CACHE_TTL).isAfter(clock.instant())){legCache.remove(key);return null;}
            return value.result();
        }
    }
    private void putCachedLeg(String key,KakaoTransitClient.Result result) {
        synchronized(legCache){
            if(legCache.size()>=CACHE_LIMIT)legCache.remove(legCache.keySet().iterator().next());
            legCache.put(key,new CachedLeg(result,clock.instant()));
        }
    }
    private record CachedLeg(KakaoTransitClient.Result result,Instant storedAt) {}
    static Day day(CourseDetailResponse.DayDto day,List<Leg> legs) {
        boolean complete=!legs.isEmpty() && legs.stream().allMatch(l->l.status().equals("OK") && l.distanceMeters()!=null && l.durationSeconds()!=null);
        return new Day(day.dayNo(),day.visitDate(),complete?legs.stream().mapToLong(Leg::distanceMeters).sum():null,
                complete?legs.stream().mapToLong(Leg::durationSeconds).sum():null,List.copyOf(legs));
    }
    static List<Stop> points(CourseDetailResponse course,CourseDetailResponse.DayDto day) {
        var points=new ArrayList<Stop>();var hotel=course.accommodation();
        if(day.items().isEmpty())return points;
        if(hotel!=null)points.add(new Stop("ACCOMMODATION",hotel.getPlaceName(),hotel.getLatitude(),hotel.getLongitude()));
        day.items().stream().sorted(Comparator.comparingInt(CourseDetailResponse.ItemDto::position)).forEach(i->
                points.add(new Stop("ITEM:"+i.id(),i.placeName(),i.latitude(),i.longitude())));
        if(hotel!=null)points.add(points.get(0));
        return List.copyOf(points);
    }
    private TransitRouteResponse unavailable(CourseDetailResponse c,String status) {
        var days=new ArrayList<Day>();for(var d:c.days()){var p=points(c,d);var legs=new ArrayList<Leg>();
            for(int i=1;i<p.size();i++)legs.add(new Leg(p.get(i-1),p.get(i),status,null,null,null,null,List.of()));days.add(day(d,legs));}
        return new TransitRouteResponse(c.id(),clock.instant(),false,0,List.copyOf(days));
    }
    static String signature(CourseDetailResponse c) {
        // Weather and tokens are deliberately excluded. Schedule/accommodation changes invalidate.
        return c.id()+":"+c.startDate()+":"+c.endDate()+":"+c.days().stream()
                .map(d->d.visitDate()+":"+points(c,d)+":"+d.items().stream().map(i->i.startTime()+"/"+i.endTime()).toList()).toList();
    }
}
