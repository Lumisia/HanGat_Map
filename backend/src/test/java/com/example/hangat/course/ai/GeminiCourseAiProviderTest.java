package com.example.hangat.course.ai;

import com.example.hangat.course.ai.CourseAiInputDto.CandidateFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.CongestionFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.GenerationMetadataDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceConstraintDto;
import com.example.hangat.course.ai.CourseAiInputDto.PlaceIdentityDto;
import com.example.hangat.course.ai.CourseAiInputDto.SelectedRegionDto;
import com.example.hangat.course.ai.CourseAiInputDto.SelectedStyleDto;
import com.example.hangat.course.ai.CourseAiInputDto.TourCategoryDto;
import com.example.hangat.course.ai.CourseAiInputDto.TravelFactDto;
import com.example.hangat.course.ai.CourseAiInputDto.TripConditionDto;
import com.example.hangat.course.ai.CourseAiInputDto.UserPreferencesDto;
import com.example.hangat.course.model.CongestionLevel;
import com.example.hangat.course.model.GenerationReason;
import com.example.hangat.course.model.PreferenceType;
import com.example.hangat.course.model.Transport;
import com.example.hangat.course.travel.DistanceCalculationMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiCourseAiProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void sendsProductionSmokeFixtureThroughMessageConverterWithoutCredential() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");

        server.expect(once(), requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-secret"))
                .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text").isNotEmpty())
                .andExpect(jsonPath("$.contents[0].role").value("user"))
                .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.type").value("object"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.days"
                        + ".items.properties.items.items.properties.candidateId.enum[0]")
                        .value("want-1"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.days"
                        + ".items.properties.items.items.properties.candidateId.enum[1]")
                        .value("normal-1"))
                .andExpect(jsonPath("$.generationConfig.responseJsonSchema.properties.days"
                        + ".items.properties.items.items.properties.candidateId.enum[2]")
                        .value("normal-2"))
                .andExpect(jsonPath("$.generationConfig.thinkingConfig.thinkingLevel").value("low"))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-secret"))))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(
                        org.hamcrest.Matchers.containsString("\"startDate\":\"2026-09-10\"")))
                .andExpect(jsonPath("$.contents[0].parts[0].text").value(
                        org.hamcrest.Matchers.containsString("\"fixedTime\":\"09:00:00\"")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"{\\"contractVersion\\":\\"1.0\\",\\"days\\":[]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.generate(smokeInput()).days()).isEmpty();
        server.verify();
    }

    @Test
    void serializesProductionSmokeBuildRequestLocally() throws Exception {
        GeminiCourseAiProvider provider = provider(RestClient.create(), "test-secret");

        String requestJson = objectMapper.writeValueAsString(provider.buildRequest(smokeInput()));
        JsonNode request = objectMapper.readTree(requestJson);
        JsonNode input = objectMapper.readTree(
                request.path("contents").path(0).path("parts").path(0).path("text").asText());

        assertThat(request.path("systemInstruction").path("parts").path(0).path("text").asText())
                .contains("candidateId는 반드시 candidates[].candidateId 중 하나를")
                .contains("각 DAY에 기본적으로 3개 장소")
                .contains("3개 배치가 불가능하면 중복 없이 2개")
                .contains("10:00:00, 14:00:00, 18:00:00")
                .contains("fixedDate와 fixedTime을 절대 덮어쓰지 않는다")
                .contains("straightDistanceMeters")
                .contains("weather")
                .doesNotContain("straightDistanceKm", "routeDistanceKm", "durationMinutes")
                .contains("새 ID를 생성");
        assertThat(request.path("generationConfig").path("responseMimeType").asText())
                .isEqualTo("application/json");
        assertThat(request.path("generationConfig").path("responseJsonSchema").path("type").asText())
                .isEqualTo("object");
        assertThat(request.path("generationConfig").path("responseJsonSchema")
                .path("properties").path("days")
                .path("items").path("properties").path("items")
                .path("items").path("properties").path("candidateId")
                .path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("want-1", "normal-1", "normal-2");
        JsonNode schema = request.path("generationConfig").path("responseJsonSchema");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties").path("contractVersion")
                .path("enum").path(0).asText()).isEqualTo("1.0");
        assertThat(schema.path("properties").path("days").path("minItems").asInt())
                .isEqualTo(1);
        JsonNode daySchema = schema.path("properties").path("days").path("items");
        assertThat(daySchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(daySchema.path("properties").path("items").path("minItems").asInt())
                .isEqualTo(1);
        assertThat(daySchema.path("properties").path("items").path("maxItems").asInt())
                .isEqualTo(3);
        assertThat(daySchema.path("properties").path("items").path("description").asText())
                .contains("기본 3개", "중복 없이 2개", "1개");
        JsonNode itemSchema = daySchema.path("properties").path("items").path("items");
        assertThat(itemSchema.path("additionalProperties").asBoolean()).isFalse();
        JsonNode startTimeSchema = itemSchema.path("properties").path("startTime");
        assertThat(startTimeSchema.has("format")).isFalse();
        assertThat(startTimeSchema.path("description").asText())
                .contains("제주 현지 시각 HH:mm:ss")
                .contains("timezone", "offset", "Z", "fractional seconds");
        assertThat(itemSchema.path("properties").path("recommendationReason")
                .path("description").asText()).contains("300자 이하");
        assertThat(itemSchema.path("properties").path("recommendationReason")
                .has("maxLength")).isFalse();
        assertThat(request.path("generationConfig").path("thinkingConfig")
                .path("thinkingLevel").asText()).isEqualTo("low");
        assertThat(input.path("trip").path("startDate").asText())
                .isEqualTo("2026-09-10");
        assertThat(input.path("hardConstraints").path("requiredCandidates")
                .path(0).path("fixedTime").asText()).isEqualTo("09:00:00");
        assertThat(input.path("candidates").size()).isEqualTo(3);
        assertThat(input.path("candidates").path(0).path("candidateId").asText())
                .isEqualTo("want-1");
        assertThat(input.path("travelFacts").path(0).path("straightDistanceMeters")
                .decimalValue()).isEqualByComparingTo("18420");
        assertThat(input.has("tripCondition")).isFalse();
        assertThat(input.has("userPreferences")).isFalse();
        assertThat(input.has("generationMetadata")).isFalse();
        assertThat(requestJson).doesNotContain("test-secret");
    }

    @Test
    void buildsCorrectionRequestWithFailureReasonAndOriginalCandidates() throws Exception {
        GeminiCourseAiProvider provider = provider(RestClient.create(), "test-secret");

        String requestJson = objectMapper.writeValueAsString(provider.buildCorrectionRequest(
                smokeInput(),
                new CourseAiResultDto("1.0", List.of()),
                CourseAiValidationCode.AI_RESULT_DUPLICATE_CANDIDATE,
                "AI 코스 결과에 같은 candidateId가 중복 배치되었습니다."));
        JsonNode request = objectMapper.readTree(requestJson);
        String correctionPrompt = request.path("contents").path(0)
                .path("parts").path(0).path("text").asText();

        assertThat(correctionPrompt)
                .contains("Validation code: AI_RESULT_DUPLICATE_CANDIDATE")
                .contains("Validation message: AI 코스 결과에 같은 candidateId가 중복 배치되었습니다.")
                .contains("이전 전체 결과 JSON:")
                .contains("\"contractVersion\":\"1.0\"")
                .contains("동일 candidateId를 재사용하지 않는다")
                .contains("후보가 부족하면 중복해서 채우지 말고")
                .contains("각 DAY에 기본적으로 3개 장소")
                .contains("3개가 불가능하면 중복 없이 2개")
                .contains("10:00:00, 14:00:00, 18:00:00")
                .contains("fixedDate와 fixedTime을 덮어쓰지 않는다")
                .contains("제주 현지 시각의 HH:mm:ss")
                .contains("timezone, UTC offset, Z, fractional seconds")
                .contains("\"candidateId\":\"want-1\"")
                .contains("\"candidateId\":\"normal-1\"")
                .doesNotContain("test-secret");
        assertThat(request.path("generationConfig").path("responseJsonSchema")
                .path("properties").path("days").path("items")
                .path("properties").path("items").path("items")
                .path("properties").path("candidateId").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("want-1", "normal-1", "normal-2");
    }

    @Test
    void parsesOnlyExactJejuLocalTimeValues() {
        GeminiCourseAiProvider provider = provider(RestClient.create(), "test-secret");

        assertThat(parseStartTime(provider, "09:30:00")).isEqualTo(LocalTime.of(9, 30));
        assertThat(parseStartTime(provider, "00:00:00")).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(parseStartTime(provider, "23:59:59")).isEqualTo(LocalTime.of(23, 59, 59));
    }

    @Test
    void rejectsOffsetFractionalAndOutOfRangeWireTimesWithoutExposingThem() {
        GeminiCourseAiProvider provider = provider(RestClient.create(), "test-secret");

        for (String invalid : List.of(
                "09:30",
                "09:30:00.000",
                "09:30:00.000Z",
                "09:30:00Z",
                "09:30:00+09:00",
                "24:00:00")) {
            assertThatThrownBy(() -> parseStartTime(provider, invalid))
                    .isInstanceOfSatisfying(CourseAiValidationException.class, exception -> {
                        assertThat(exception.getFailureType())
                                .isEqualTo(CourseAiFailureType.VALIDATION_ERROR);
                        assertThat(exception.getCode())
                                .isEqualTo(CourseAiValidationCode.AI_RESULT_START_TIME_FORMAT_INVALID);
                        assertThat(exception.getMessage())
                                .contains("제주 현지 시각 HH:mm:ss")
                                .doesNotContain(invalid);
                    });
        }
    }

    @Test
    void parsesOfficialEnvelopeWithUnknownFields() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        server.expect(once(), requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{
                        "text":"{\\\"contractVersion\\\":\\\"1.0\\\",\\\"days\\\":[]}",
                        "thoughtSignature":"opaque-signature"}]},"finishReason":"STOP"}],
                        "usageMetadata":{"promptTokenCount":1},
                        "modelVersion":"gemini-3.5-flash","responseId":"response-id"}
                        """, MediaType.APPLICATION_JSON));

        CourseAiResultDto result = provider.generate(smokeInput());

        assertThat(result.contractVersion()).isEqualTo("1.0");
        assertThat(result.days()).isEmpty();
        server.verify();
    }

    @Test
    void mapsBadRequestWithSafeDiagnostics() {
        assertFailureForStatus(
                400, "INVALID_ARGUMENT", "INVALID_JSON_PAYLOAD",
                CourseAiFailureType.PROVIDER_ERROR);
    }

    @Test
    void mapsForbiddenWithSafeDiagnostics() {
        assertFailureForStatus(
                403, "PERMISSION_DENIED", "API_KEY_INVALID",
                CourseAiFailureType.PROVIDER_ERROR);
    }

    @Test
    void mapsNotFoundWithSafeDiagnostics() {
        assertFailureForStatus(
                404, "NOT_FOUND", "MODEL_NOT_FOUND",
                CourseAiFailureType.PROVIDER_ERROR);
    }

    @Test
    void mapsRateLimitWithoutChangingFailureType() {
        assertFailureForStatus(
                429, "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED",
                CourseAiFailureType.RATE_LIMIT);
    }

    @Test
    void mapsServerErrorWithSafeDiagnostics() {
        assertFailureForStatus(
                500, "INTERNAL", "BACKEND_ERROR",
                CourseAiFailureType.TEMPORARILY_UNAVAILABLE);
    }

    @Test
    void retriesTransientStatusesAndReturnsTheSuccessfulResult() {
        for (int status : List.of(429, 502, 503, 504)) {
            assertRetriesThenSucceeds(status);
        }
    }

    @Test
    void retriesReadTimeoutOnlyOnceThenStops() {
        RestClient restClient = mock(RestClient.class);
        when(restClient.post()).thenThrow(new ResourceAccessException(
                "sensitive network detail", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> provider(restClient, "test-secret").generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception ->
                        assertThat(exception.getFailureType())
                                .isEqualTo(CourseAiFailureType.TEMPORARILY_UNAVAILABLE));
        org.mockito.Mockito.verify(restClient, org.mockito.Mockito.times(2)).post();
    }

    @Test
    void retriesReadTimeoutAndReturnsTheSuccessfulResult() {
        assertNetworkFailureThenSucceeds(new SocketTimeoutException("Read timed out"));
    }

    @Test
    void retriesConnectionTimeoutAndReturnsTheSuccessfulResult() {
        assertNetworkFailureThenSucceeds(new SocketTimeoutException("connect timed out"));
    }

    @Test
    void mapsMalformedSuccessEnvelopeToInvalidResponse() {

        RestClient.Builder invalidBuilder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        GeminiCourseAiProvider invalidProvider = provider(invalidBuilder.build(), "test-secret");
        invalidServer.expect(once(), requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"not-json"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        assertFailure(invalidProvider, CourseAiFailureType.INVALID_RESPONSE);

        RestClient.Builder malformedBuilder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer malformedServer = MockRestServiceServer.bindTo(malformedBuilder).build();
        GeminiCourseAiProvider malformedProvider = provider(malformedBuilder.build(), "test-secret");
        malformedServer.expect(once(), requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("{sensitive-malformed", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> malformedProvider.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.INVALID_RESPONSE);
                    assertThat(exception.getMessage())
                            .contains("PHASE=PARSE_ENVELOPE")
                            .contains("HTTP_STATUS=200")
                            .contains("CONTENT_TYPE=application_json")
                            .contains("BODY_PRESENT=true")
                            .contains("BODY_LENGTH_CATEGORY=SHORT")
                            .contains("BODY_FIRST_CHAR_TYPE=JSON_OBJECT")
                            .contains("EXCEPTION_TYPE=JsonParseException")
                            .doesNotContain("sensitive-malformed")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void mapsEmptyEnvelopeWithSafeMetadata() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        server.expect(once(),
                        requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.INVALID_RESPONSE);
                    assertThat(exception.getMessage())
                            .contains("PHASE=PARSE_ENVELOPE")
                            .contains("HTTP_STATUS=200")
                            .contains("CONTENT_TYPE=application_json")
                            .contains("BODY_PRESENT=false")
                            .contains("BODY_LENGTH_CATEGORY=EMPTY")
                            .contains("BODY_FIRST_CHAR_TYPE=NONE")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void mapsHtmlEnvelopeWithoutExposingBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        server.expect(once(),
                        requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("<html>sensitive-body</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> provider.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.INVALID_RESPONSE);
                    assertThat(exception.getMessage())
                            .contains("HTTP_STATUS=200")
                            .contains("CONTENT_TYPE=text_html")
                            .contains("BODY_PRESENT=true")
                            .contains("BODY_LENGTH_CATEGORY=SHORT")
                            .contains("BODY_FIRST_CHAR_TYPE=HTML_LIKE")
                            .doesNotContain("sensitive-body")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void mapsNonJsonContentTypeWithoutParsingBody() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        server.expect(once(), requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withSuccess("{\"sensitive\":true}", MediaType.TEXT_PLAIN));

        assertThatThrownBy(() -> provider.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.INVALID_RESPONSE);
                    assertThat(exception.getMessage())
                            .contains("HTTP_STATUS=200")
                            .contains("CONTENT_TYPE=text_plain")
                            .contains("BODY_PRESENT=true")
                            .contains("BODY_FIRST_CHAR_TYPE=JSON_OBJECT")
                            .doesNotContain("sensitive")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void mapsMissingApiKeyToConfigurationError() {
        assertFailure(provider(RestClient.create(), ""), CourseAiFailureType.CONFIGURATION_ERROR);
    }

    @Test
    void mapsUnhandledExceptionWithSafeTypeOnly() {
        RestClient restClient = mock(RestClient.class);
        when(restClient.post()).thenThrow(new RestClientException("sensitive request detail"));

        assertThatThrownBy(() -> provider(restClient, "test-secret").generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.PROVIDER_ERROR);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.getMessage())
                            .contains("PHASE=HTTP_EXCHANGE")
                            .contains("EXCEPTION_TYPE=RestClientException")
                            .doesNotContain("CAUSE_TYPE=")
                            .doesNotContain("ROOT_CAUSE_TYPE=")
                            .contains("HOST=gemini.test")
                            .contains("MODEL=gemini-test")
                            .doesNotContain("sensitive request detail")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void mapsGenericCauseChainWithoutMessagesOrCredential() {
        RestClient restClient = mock(RestClient.class);
        RestClientException failure = new RestClientException(
                "sensitive top detail",
                new HttpMessageNotWritableException(
                        "sensitive conversion detail",
                        new IllegalArgumentException("sensitive root detail")));
        when(restClient.post()).thenThrow(failure);

        assertThatThrownBy(() -> provider(restClient, "test-secret").generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.PROVIDER_ERROR);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.getMessage())
                            .contains("PHASE=REQUEST_SERIALIZATION")
                            .contains("EXCEPTION_TYPE=RestClientException")
                            .contains("CAUSE_TYPE=HttpMessageNotWritableException")
                            .contains("ROOT_CAUSE_TYPE=IllegalArgumentException")
                            .doesNotContain("sensitive top detail")
                            .doesNotContain("sensitive conversion detail")
                            .doesNotContain("sensitive root detail")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void identifiesResponseExtractionFromRestClientIoCause() {
        RestClient restClient = mock(RestClient.class);
        when(restClient.post()).thenThrow(new RestClientException(
                "sensitive extraction detail", new IOException("sensitive io detail")));

        assertThatThrownBy(() -> provider(restClient, "test-secret").generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.PROVIDER_ERROR);
                    assertThat(exception.getMessage())
                            .contains("PHASE=RESPONSE_EXTRACTION")
                            .contains("EXCEPTION_TYPE=RestClientException")
                            .contains("CAUSE_TYPE=IOException")
                            .doesNotContain("sensitive extraction detail")
                            .doesNotContain("sensitive io detail")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void preservesResourceAccessNetworkDiagnostics() {
        RestClient restClient = mock(RestClient.class);
        when(restClient.post()).thenThrow(new ResourceAccessException(
                "sensitive network detail", new SocketTimeoutException("sensitive timeout detail")));

        assertThatThrownBy(() -> provider(restClient, "test-secret").generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.TEMPORARILY_UNAVAILABLE);
                    assertThat(exception.getMessage())
                            .contains("PHASE=HTTP_EXCHANGE")
                            .contains("NETWORK=READ_TIMEOUT")
                            .doesNotContain("sensitive network detail")
                            .doesNotContain("sensitive timeout detail")
                            .doesNotContain("test-secret");
                });
    }

    @Test
    void identifiesBuildRequestPhaseWithoutLeakingMessage() {
        CourseAiPrompt failingPrompt = mock(CourseAiPrompt.class);
        when(failingPrompt.systemInstruction())
                .thenThrow(new IllegalStateException("sensitive build detail"));

        assertThatThrownBy(() -> provider(
                RestClient.create(), "test-secret", failingPrompt).generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType())
                            .isEqualTo(CourseAiFailureType.PROVIDER_ERROR);
                    assertThat(exception.getMessage())
                            .contains("PHASE=BUILD_REQUEST")
                            .contains("EXCEPTION_TYPE=IllegalStateException")
                            .doesNotContain("sensitive build detail")
                            .doesNotContain("test-secret");
                });
    }

    private void assertFailureForStatus(
            int status,
            String googleStatus,
            String googleReason,
            CourseAiFailureType expected
    ) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        int attempts = status == 408 || status == 429 || status == 500
                || status == 502 || status == 503 || status == 504 ? 3 : 1;
        server.expect(times(attempts),
                        requestTo("http://gemini.test/v1beta/models/gemini-test:generateContent"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":%d,"message":"sensitive upstream detail",
                                "status":"%s","details":[{"reason":"%s"}]}}
                                """.formatted(status, googleStatus, googleReason)));

        assertThatThrownBy(() -> provider.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class, exception -> {
                    assertThat(exception.getFailureType()).isEqualTo(expected);
                    assertThat(exception.getCause()).isNull();
                    assertThat(exception.getMessage())
                            .contains("PHASE=HTTP_EXCHANGE")
                            .contains("HTTP_STATUS=" + status)
                            .contains("GOOGLE_STATUS=" + googleStatus)
                            .contains("GOOGLE_REASON=" + googleReason)
                            .contains("HOST=gemini.test")
                            .contains("MODEL=gemini-test")
                            .doesNotContain("sensitive upstream detail")
                            .doesNotContain("test-secret");
                });
        server.verify();
    }

    private void assertFailure(GeminiCourseAiProvider provider, CourseAiFailureType expected) {
        assertThatThrownBy(() -> provider.generate(input()))
                .isInstanceOfSatisfying(CourseAiException.class,
                        exception -> assertThat(exception.getFailureType()).isEqualTo(expected));
    }

    private LocalTime parseStartTime(GeminiCourseAiProvider provider, String startTime) {
        String resultJson = """
                {"contractVersion":"1.0","days":[{"date":"2026-08-28","items":[{
                "candidateId":"want-1","startTime":"%s","recommendationReason":"입력 근거"
                }]}]}
                """.formatted(startTime);
        CourseAiResultDto result = provider.parseResult(
                new GeminiCourseAiProvider.GeminiGenerateResponse(List.of(
                        new GeminiCourseAiProvider.GeminiCandidate(
                                new GeminiCourseAiProvider.GeminiResponseContent(List.of(
                                        new GeminiCourseAiProvider.GeminiResponsePart(resultJson)))))));
        return result.days().get(0).items().get(0).startTime();
    }

    private GeminiCourseAiProvider provider(RestClient restClient, String apiKey) {
        return provider(restClient, apiKey, new CourseAiPrompt(objectMapper));
    }

    private GeminiCourseAiProvider provider(
            RestClient restClient,
            String apiKey,
            CourseAiPrompt prompt
    ) {
        GeminiProperties properties = new GeminiProperties(
                "http://gemini.test/v1beta", apiKey, "gemini-test",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new GeminiCourseAiProvider(
                restClient, properties, prompt, objectMapper, noSleepRetryPolicy());
    }

    private GeminiRetryPolicy noSleepRetryPolicy() {
        return new GeminiRetryPolicy(
                duration -> { },
                () -> 0L,
                Clock.fixed(Instant.parse("2026-09-03T00:00:00Z"), ZoneOffset.UTC));
    }

    private void assertRetriesThenSucceeds(int status) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        String endpoint = "http://gemini.test/v1beta/models/gemini-test:generateContent";

        server.expect(once(), requestTo(endpoint))
                .andRespond(withStatus(org.springframework.http.HttpStatus.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":" + status + "}}"));
        server.expect(once(), requestTo(endpoint))
                .andRespond(withSuccess(successEnvelope(), MediaType.APPLICATION_JSON));

        CourseAiResultDto result = provider.generate(input());

        assertThat(result.contractVersion()).isEqualTo("1.0");
        server.verify();
    }

    private void assertNetworkFailureThenSucceeds(IOException failure) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://gemini.test/v1beta");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GeminiCourseAiProvider provider = provider(builder.build(), "test-secret");
        String endpoint = "http://gemini.test/v1beta/models/gemini-test:generateContent";

        server.expect(once(), requestTo(endpoint)).andRespond(request -> {
            throw failure;
        });
        server.expect(once(), requestTo(endpoint))
                .andRespond(withSuccess(successEnvelope(), MediaType.APPLICATION_JSON));

        CourseAiResultDto result = provider.generate(input());

        assertThat(result.contractVersion()).isEqualTo("1.0");
        server.verify();
    }

    private String successEnvelope() {
        return """
                {"candidates":[{"content":{"parts":[{
                "text":"{\\\"contractVersion\\\":\\\"1.0\\\",\\\"days\\\":[]}"
                }]}}]}
                """;
    }

    private CourseAiInputDto input() {
        return new CourseAiInputDto(
                "1.0",
                new TripConditionDto(
                        LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-29"),
                        2, 500000, Transport.RENTAL_CAR),
                new UserPreferencesDto(List.of(), List.of(), List.of(), List.of(), null),
                List.of(), List.of(), null);
    }

    private CourseAiInputDto smokeInput() {
        LocalDate firstDay = LocalDate.of(2026, 9, 10);
        PlaceIdentityDto wantIdentity = new PlaceIdentityDto("want-1", null, null, null);
        PlaceConstraintDto want = new PlaceConstraintDto(
                wantIdentity, "성산일출봉", "제주특별자치도 서귀포시 성산읍",
                33.458, 126.942, "관광지", PreferenceType.WANT,
                firstDay, LocalTime.of(9, 0));
        PlaceConstraintDto avoid = new PlaceConstraintDto(
                new PlaceIdentityDto("avoid-1", null, null, null),
                "금지장소", null, null, null, null, PreferenceType.AVOID, null, null);

        CandidateFactDto wantedCandidate = candidate(
                wantIdentity, "성산일출봉", PreferenceType.WANT, List.of("NATURE"),
                firstDay, new BigDecimal("27.50"), CongestionLevel.QUIET, 33.458, 126.942);
        CandidateFactDto natureCandidate = candidate(
                new PlaceIdentityDto("normal-1", null, null, null),
                "비자림", null, List.of("NATURE"), firstDay,
                new BigDecimal("52.00"), CongestionLevel.NORMAL, 33.491, 126.811);
        CandidateFactDto cafeCandidate = candidate(
                new PlaceIdentityDto("normal-2", null, null, null),
                "월정리 카페거리", null, List.of("CAFE"), firstDay,
                null, null, 33.556, 126.795);

        return new CourseAiInputDto(
                "1.0",
                new TripConditionDto(firstDay, firstDay.plusDays(1), 2, 300000, Transport.RENTAL_CAR),
                new UserPreferencesDto(
                        List.of(new SelectedRegionDto(null, "EAST", "동부")),
                        List.of(
                                new SelectedStyleDto(null, "NATURE", "자연", BigDecimal.ONE),
                                new SelectedStyleDto(null, "CAFE", "카페", BigDecimal.ONE)),
                        List.of(want), List.of(avoid), null),
                List.of(wantedCandidate, natureCandidate, cafeCandidate),
                List.of(new TravelFactDto(
                        "want-1", "성산일출봉", "normal-1", "비자림",
                        new BigDecimal("18.42"), DistanceCalculationMethod.HAVERSINE,
                        null, null, Transport.RENTAL_CAR, null, null)),
                new GenerationMetadataDto(
                        GenerationReason.INITIAL, "smoke-v1", "smoke-request"));
    }

    private CandidateFactDto candidate(
            PlaceIdentityDto identity,
            String name,
            PreferenceType preferenceType,
            List<String> styles,
            LocalDate date,
            BigDecimal rate,
            CongestionLevel level,
            double latitude,
            double longitude
    ) {
        List<CongestionFactDto> congestion = rate == null
                ? List.of()
                : List.of(new CongestionFactDto(date, rate, level));
        return new CandidateFactDto(
                identity, name, "제주특별자치도", latitude, longitude,
                new TourCategoryDto("A01", null, null), "EAST", preferenceType,
                styles, congestion, null);
    }
}
