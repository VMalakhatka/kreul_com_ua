package org.example.proect.lavka.config;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.lang.NonNull;

public class JacksonMessageConverter extends Jackson2JsonMessageConverter {
    public JacksonMessageConverter() {
        super();
    }

    @Override
    @NonNull
    public Object fromMessage(Message message) {
        message.getMessageProperties().setContentType("application/json");
        return super.fromMessage(message);
    }
}