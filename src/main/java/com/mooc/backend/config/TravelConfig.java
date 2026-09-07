package com.mooc.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 旅行实用工具模块装配（change: add-travel-services，task 1.1 / 3.2）。
 *
 * <p>照 {@code MessagingConfig} / {@code AuthConfig} 的既有做法，用
 * {@code @EnableConfigurationProperties} 显式注册本模块的绑定类，而不是给
 * {@code BackendApplication} 加 {@code @ConfigurationPropertiesScan}——后者会一次性改变
 * 全仓的绑定发现方式，影响面远超本 change。
 */
@Configuration
@EnableConfigurationProperties(TravelProperties.class)
public class TravelConfig {

    /**
     * 出网用的 {@link RestClient}，汇率与天气客户端共用（两者的超时要求相同）。
     *
     * <p><b>超时必须配在这里，不能配在客户端的构造器里。</b> 这不是风格取舍，是可测性问题：
     * {@code MockRestServiceServer.bindTo(builder)} 的做法是往 builder 上装一个假的
     * {@code ClientHttpRequestFactory}，客户端构造时若再调一次 {@code requestFactory(...)}，
     * 就把那个假 factory 顶掉——测试会**静默地打到真实上游**（本 change 实测踩过：断言 4 个币种
     * 却收到 29 个真实币种）。客户端只接一个装配好的 {@code RestClient}，这条路就堵死了。
     *
     * <p>两个超时都显式设定：不设则底层默认可能是无限等待，一个挂住的上游会占满调度线程。
     * 不改全局的 {@code spring.http.client.*}——那会顺带作用于将来任何别处的出网客户端。
     */
    @Bean
    RestClient travelRestClient(RestClient.Builder builder, TravelProperties props) {
        TravelProperties.Client client = props.client();
        return builder
                .requestFactory(ClientHttpRequestFactoryBuilder.detect()
                        .build(ClientHttpRequestFactorySettings.defaults()
                                .withConnectTimeout(client.connectTimeout())
                                .withReadTimeout(client.readTimeout())))
                .build();
    }
}
