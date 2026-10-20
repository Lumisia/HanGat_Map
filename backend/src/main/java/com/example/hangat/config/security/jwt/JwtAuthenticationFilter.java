package com.example.hangat.config.security.jwt;

import com.example.hangat.common.exception.BaseException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final JwtAuthenticationEntryPoint entryPoint;
    private final com.example.hangat.user.repository.UserRepository users;

    /**
     *  인증 API는 Access token과 무관하게 접근한다.
     *  특히 만료된 토큰을 가진상태에서도 reissue가 가능해야 함.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return request.getServletPath().startsWith("/auth");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String token = resolveToken(request);

        if(token == null) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Long userId = jwtProvider.parseUserId(token);
            var user = users.findById(userId).orElseThrow(() -> new BaseException(
                    com.example.hangat.common.model.BaseResponseStatus.JWT_INVALID));
            if (!user.canLogin() || user.getAuthVersion() != jwtProvider.parseAuthVersion(token)) {
                throw new BaseException(com.example.hangat.common.model.BaseResponseStatus.JWT_INVALID);
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of()
                    );
            SecurityContext context =
                    SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        } catch (BaseException e) {
            SecurityContextHolder.clearContext();
            entryPoint.write(response, e.getStatus());

            return;
        }
        filterChain.doFilter(request, response);
    }
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 인증 스킴(scheme)은 대소문자를 무시하는걸로 설정
        if(header == null || !header.regionMatches(
                true,
                0,
                BEARER_PREFIX,
                0,
                BEARER_PREFIX.length()
        )) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
