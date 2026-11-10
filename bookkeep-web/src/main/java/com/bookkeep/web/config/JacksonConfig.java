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
 * 金额一律序列化成字符串（避免前端浮点误差），日期格式固定为 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss。
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