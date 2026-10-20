package com.example.hangat.user.controller;

import com.example.hangat.common.model.BaseResponse;
import com.example.hangat.config.security.cookie.AuthCookieManager;
import com.example.hangat.user.service.AccountWithdrawalService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
public class AccountWithdrawalController {
    private final AccountWithdrawalService service;
    private final AuthCookieManager cookies;
    public record Request(@NotBlank String email) {}
    @PostMapping("/users/me/withdrawal")
    public BaseResponse<AccountWithdrawalService.WithdrawalResult> withdraw(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody Request request, HttpServletResponse response) {
        var result = service.withdraw(userId, request.email());
        cookies.clearRefreshCookie(response); cookies.clearRecoveryCookie(response);
        response.setHeader("Cache-Control", "no-store");
        return BaseResponse.success(result);
    }
    @GetMapping("/auth/withdrawal")
    public BaseResponse<AccountWithdrawalService.RecoveryDetails> details(
            @CookieValue(name = AuthCookieManager.RECOVERY_COOKIE, required = false) String token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return BaseResponse.success(service.details(token));
    }
    @PostMapping("/auth/withdrawal/cancel") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void cancel(@CookieValue(name = AuthCookieManager.RECOVERY_COOKIE, required = false) String token,
            HttpServletResponse response) {
        service.cancel(token); cookies.clearRecoveryCookie(response); cookies.clearRefreshCookie(response);
    }
    @PostMapping("/auth/withdrawal/decline") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void decline(@CookieValue(name = AuthCookieManager.RECOVERY_COOKIE, required = false) String token,
            HttpServletResponse response) {
        service.decline(token); cookies.clearRecoveryCookie(response);
    }
}
