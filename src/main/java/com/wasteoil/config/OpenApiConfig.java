package com.wasteoil.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档配置。服务为纯后端，通过 Swagger UI 暴露全部接口。
 * 鉴权为轻量请求头（X-Role / X-Account-Id），在此声明为全局请求头便于在 UI 中调试。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI wasteOilOpenApi() {
        return new OpenAPI().info(new Info()
                .title("城市餐厨废油回收预约称重联单合规追踪服务 API")
                .version("1.0.0")
                .description("预约→派单→到店核验→称重（含蓝牙秤离线拍照补录+人工复核）→运输轨迹→"
                        + "处置厂二次称重→合规闭环→结算 全链路接口。调试时请在 Authorize 中填写 "
                        + "X-Role（MERCHANT/RECYCLER/DRIVER/PLANT/REGULATOR）与 X-Account-Id。"));
    }

    /** 为所有 /api 接口补充统一的鉴权请求头参数 */
    @Bean
    public OpenApiCustomizer globalHeadersCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/")) {
                return;
            }
            item.readOperations().forEach(op -> op
                    .addParametersItem(new HeaderParameter()
                            .name("X-Role")
                            .description("调用方角色：MERCHANT/RECYCLER/DRIVER/PLANT/REGULATOR")
                            .required(false)
                            .schema(new io.swagger.v3.oas.models.media.StringSchema()))
                    .addParametersItem(new HeaderParameter()
                            .name("X-Account-Id")
                            .description("调用方账号，如 driver-1、recycler-1、regulator-1")
                            .required(false)
                            .schema(new io.swagger.v3.oas.models.media.StringSchema())));
        });
    }
}
