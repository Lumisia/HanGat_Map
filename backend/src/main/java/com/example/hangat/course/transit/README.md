# AI course transit contract

Official references checked 2026-09-07:
- https://developers.kakao.com/docs/ko/kakaomap/rest-api#public-traffic
- https://developers.kakao.com/docs/ko/getting-started/quota
- https://developers.kakao.com/docs/ko/kakaomap/common
- https://developers.kakao.com/terms/ko/site-policies (5.20 cache freshness)

GET /courses/{id}/routes/transit reuses CourseQueryService permission checks, then queries outside its DB transaction.
Provider GET /v2/routing/publictraffic uses KakaoAK REST authentication and WGS84 start_x/start_y/end_x/end_y.
No departure date/time parameter is documented. Display current-query estimates, not future confirmed timetables.
Metres and seconds are passed through; totalTime is authoritative and is never added to step times.
The specification does not define wait-time inclusion or an explicit ranking flag: first provider route wins.
Bus names, stops, transfers are provided; absence of a route does not prove end of service.
No fare is included in the travel budget. No car access-point substitution.

Operational gate: kakao-transit.enabled defaults true because the app's Kakao Map activation and free-quota eligibility are confirmed. Set it to false explicitly to disable transit routing. Existing kakao-local.rest-key is reused, and a missing key still disables provider calls. No billing activation.
The published free allowance is 1,000 public-transit requests/day for an eligible first-enabled app, not proof of this app's eligibility.

Connect timeout 2s; per attempt whole-response timeout up to 6s, clipped to a 26s request budget.
At most 12 HTTP attempts per course request, at most one retry per leg for I/O/502/503/504/-7/-603, 100–200ms jitter.
Two active course requests maximum, sequential legs, duplicate callers wait at most 28s.
401/403/429 and -10/-11/-13/-903 stop remaining calls and open a one-minute local circuit.
Successful legs are cached in process for 60 seconds from insertion, bounded to 256 entries, for user experience only.
The leg key includes endpoint contract, input/output coordinate systems, exact coordinates and direction. Reads do not extend expiry.
Course-level duplicate requests and cross-course duplicate legs are both single-flight; cached legs do not consume the per-request provider-attempt budget.
Partial responses reuse successful legs and retry only uncached/failed legs. Failures are never successful cache entries.
HTTP response is no-store. No persistent/provider data store or tokens in keys; missing totals remain null.
