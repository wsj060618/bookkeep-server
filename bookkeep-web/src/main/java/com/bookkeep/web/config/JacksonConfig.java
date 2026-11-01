package com.bookkeep.web.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 序列化约定（PLAN §5.0 第 2、3 条），必须在写第一个带金额/日期的页面之前落地。
 * <ol>
 *   <li><strong>BigDecimal 一律序列化成字符串</strong>：{@code 620.00} → {@code "620.00"}。
 *       JS 的 number 是 IEEE 754 双精度，{@code 0.1 + 0.2 !== 0.3}，
 *       前端一旦对金额做加法/比较就会出分位误差。字符串则由前端原样展示、或交给
 *       decimal 库计算，永远不会被隐式转成浮点。</li>
 *   <li><strong>LocalDate → yyyy-MM-dd</strong>、<strong>LocalDateTime → yyyy-MM-dd HH:mm:ss</strong>：
 *       {@code spring.jackson.date-format} 只对 {@code java.util.Date} 生效，
 *       Java 8 时间类型必须单独注册序列化器，否则会输出 {@code [2026,11,8]} 这种数组。</li>
 * </ol>
 * <p>反序列化也注册了对应格式，所以请求体里传 {@code "2026-11-08"} 能正确解析成 {@link java.time.LocalDate}。
 * <p>注意：金额是字符串不代表后端放宽校验，{@code @Digits} 仍然限制两位小数。
 */
@Configuration
public class JacksonConfig {

    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bookkeepJacksonCustomizer() {
        return builder -> {
            builder.serializerByType(BigDecimal.class, ToStringSerializer.instance);
            builder.serializers(new LocalDateSerializer(DATE), new LocalDateTimeSerializer(DATE_TIME));
            builder.deserializers(new LocalDateDeserializer(DATE), new LocalDateTimeDeserializer(DATE_TIME));
        };
    }
}