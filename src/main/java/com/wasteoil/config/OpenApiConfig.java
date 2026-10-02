package com.wasteoil.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 文档配置。Swagger UI: /swagger-ui.html，OpenAPI JSON: /v3/api-docs。
 * 轻量鉴权通过请求头 X-Role / X-Account-Id 传入（见 README 测试账号）。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI wasteOilOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("城市餐厨废油回收预约称重联单合规追踪服务")
                        .version("1.0.0")
                        .description("预约 → 派单 → 到店核验 → 称重（含设备离线补录与人工复核）→ "
                                + "运输轨迹 → 二次称重 → 合规闭环 → 结算 全链路 API。"
                                + "调用时在请求头携带 X-Role（MERCHANT/RECYCLER/DRIVER/PLANT/REGULATOR）"
                                + "与 X-Account-Id。"))
                .servers(List.of(new Server().url("http://localhost:8080").description("本地")));
    }
}
