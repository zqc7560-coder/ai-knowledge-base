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
                        // 找回密码（M11.3）三端点匿名可用；滥用防护全在 PasswordResetService 限流
                        "/api/auth/password/**",
                        // 运行指标（M12.3）：Prometheus 拉取是机器行为无会话 token，故放行。
                        // 边界：仅 health 匿名(且 show-details=when_authorized，只回 UP)与 prometheus 文本；
                        // 部署上 backend 端口只绑 127.0.0.1（compose），公网/nginx 均不透出——
                        // 公网暴露形态必须在 nginx/防火墙层再挡一层 /actuator（docs/08 已列）。
                        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                        "/api/ping",
                        // 分享只读视图：凭证即授权（随机 token 不可枚举），无登录可访问；
                        // 端点本身只回会话消息，不含库元信息且不可提问（ShareFeedbackController 注释）
                        "/api/public/**",
                        // 开放 API：Bearer API Key 自鉴权（ApiKeyService，非 Sa-Token 会话体系）
                        "/api/open/**",
                        "/api/docs/**", "/api/openapi.json", "/api/swagger-ui/**");
    }
}
