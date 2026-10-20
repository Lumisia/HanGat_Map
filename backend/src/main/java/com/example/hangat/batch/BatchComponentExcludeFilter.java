package com.example.hangat.batch;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

import java.io.IOException;
import java.util.Set;

/**
 * 배치의 API 전용 빈 생성 차단 - 회원 인증·메일·사진 저장에 필요한 비밀값을 요구하지 않게 한다.
 * 엔티티와 Repository는 공유하고, 적재·샘플 생성·알림 저장 서비스는 그대로 사용한다.
 * API 프로필의 컴포넌트 스캔에는 영향을 주지 않는다.
 */
public final class BatchComponentExcludeFilter implements TypeFilter, EnvironmentAware {
    private static final String ROOT = "com.example.hangat.";
    private static final Set<String> API_ONLY_CLASSES = Set.of(
            ROOT + "config.StorageConfig", ROOT + "config.SwaggerConfig",
            ROOT + "course.CourseClaimTokenService", ROOT + "course.CourseClaimService",
            ROOT + "course.CourseAccommodationService"
    );
    private final TypeFilter controller = new AnnotationTypeFilter(Controller.class);
    private final TypeFilter advice = new AnnotationTypeFilter(ControllerAdvice.class);
    private boolean batch;

    /** Spring이 현재 실행 프로필을 전달한다. 환경변수 문자열을 따로 해석하지 않는다. */
    @Override
    public void setEnvironment(Environment environment) {
        batch = environment.acceptsProfiles(Profiles.of("batch"));
    }

    /** 컨트롤러와 그 API 전용 의존성만 제외하며 DB 모델·Repository는 제외하지 않는다. */
    @Override
    public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
        if (!batch) return false;
        String name = reader.getClassMetadata().getClassName();
        return API_ONLY_CLASSES.contains(name)
                || name.startsWith(ROOT + "user.service.")
                || name.startsWith(ROOT + "review.service.")
                || name.startsWith(ROOT + "config.security.")
                || name.startsWith(ROOT + "config.mail.")
                || controller.match(reader, factory)
                || advice.match(reader, factory);
    }
}
