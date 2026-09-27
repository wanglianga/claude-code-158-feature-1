package com.wasteoil.config;

import com.wasteoil.common.ActorRole;
import com.wasteoil.common.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;

/**
 * 轻量鉴权：从请求头解析调用方角色与账号。
 * 演示环境使用明文头，生产应替换为真实的认证体系（如 OAuth2 / 网关鉴权）。
 *
 * 请求头：
 *   X-Role: MERCHANT | RECYCLER | DRIVER | PLANT | REGULATOR
 *   X-Account-Id: 账号标识（如 store-1、driver-1）
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String roleHeader = request.getHeader("X-Role");
        String accountId = request.getHeader("X-Account-Id");
        if (roleHeader != null && !roleHeader.isBlank()) {
            try {
                AuthContext.set(ActorRole.valueOf(roleHeader.trim().toUpperCase(Locale.ROOT)),
                        accountId == null ? "anonymous" : accountId.trim());
            } catch (IllegalArgumentException ignored) {
                // 非法角色头按未认证处理，由具体接口的 require 拦截
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }
}
