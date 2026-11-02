package com.kb.security.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 拦截链配置。
 *
 * <h3>两条拦截规则的关系</h3>
 * ① SaInterceptor(handle -> StpUtil.checkLogin())：路由式"白名单外一律要求登录"，
 *    粗闸门，保证未登录者连业务 Controller 都进不来；
 * ② isAnnotation(true)：开启 @SaCheckRole/@SaCheckPermission 注解校验，
 *    <b>默认是关的</b>——不开的话 AdminController 类上的 @SaCheckRole("ADMIN") 静默失效，
 *    普通用户也能进后台（实测 Sa-Token 1.38 默认行为，极易踩）。
 *
 * <h3>白名单为什么包含 /api/ping</h3>
 * 部署健康检查（compose/监控）不带 token；它只回基础设施连通性，不含业务数据。
 * /api/admin/ai-check 不在白名单：它虽只回掩码，但属于运维信息，需登录后才可访问。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handle -> StpUtil.checkLogin())
                        .isAnnotation(true))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/ping",
                        "/api/docs/**", "/api/openapi.json", "/api/swagger-ui/**");
    }
}
