package com.wasteoil.common;

/**
 * 当前请求的调用方身份（ThreadLocal）。由 AuthInterceptor 填充。
 */
public final class AuthContext {

    private static final ThreadLocal<ActorRole> ROLE = new ThreadLocal<>();
    private static final ThreadLocal<String> ACCOUNT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(ActorRole role, String accountId) {
        ROLE.set(role);
        ACCOUNT.set(accountId);
    }

    public static ActorRole role() {
        return ROLE.get();
    }

    public static String accountId() {
        return ACCOUNT.get();
    }

    public static boolean is(ActorRole role) {
        return ROLE.get() == role;
    }

    public static void require(ActorRole... roles) {
        ActorRole current = ROLE.get();
        for (ActorRole r : roles) {
            if (current == r) {
                return;
            }
        }
        throw BizException.forbidden("当前角色无权执行该操作，需要角色: " + java.util.Arrays.toString(roles));
    }

    public static void clear() {
        ROLE.remove();
        ACCOUNT.remove();
    }
}
