package org.example.proect.lavka.config;

import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String QUEUE_NAME = "myQueue";
    public static final String QUEUE_FOR_MOVE = "MoveQueue";
    public static final String QUEUE_FOR_REST = "RestQueue";
    public static final String QUEUE_FOR_SOCK_PARAM = "StockParamQueue";
    public static final String QUEUE_FOR_ASSEMBLE = "AssembleQueue";
    public static final String QUEUE_HEALS = "HealsQueue";

    @Bean
    public ConnectionFactory connectionFactory() {
        return new CachingConnectionFactory("localhost");
    }

    @Bean
    public AmqpAdmin amqpAdmin() {
        return new RabbitAdmin(connectionFactory());
    }

    @Bean
    public MessageConverter messageConverter() {
        JacksonMessageConverter jsonMessageConverter = new JacksonMessageConverter();
        jsonMessageConverter.setClassMapper(jsonMessageConverter.getJavaTypeMapper());

        return jsonMessageConverter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate() {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory());
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }

    @Bean
    public Queue myQueue() {
        return new Queue(QUEUE_NAME);
    }

    @Bean
    public Queue forMoveQueue() {
        return new Queue(QUEUE_FOR_MOVE);
    }

    @Bean
    public Queue restQueue() {
        return new Queue(QUEUE_FOR_REST);
    }

    @Bean
    public Queue stockParamQueue() {
        return new Queue(QUEUE_FOR_SOCK_PARAM);
    }

    @Bean
    public Queue assembleQueue() {return new Queue(QUEUE_FOR_ASSEMBLE);}
    @Bean
    public Queue healsQueue() {
        return new Queue(QUEUE_HEALS);
    }
}
