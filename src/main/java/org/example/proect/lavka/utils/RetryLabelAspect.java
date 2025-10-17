package org.example.proect.lavka.utils;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class RetryLabelAspect {

    // Срабатывает если @Retryable есть на методе ИЛИ на классе,
    // и @RetryLabel есть на методе ИЛИ на классе.
    @Around("( @annotation(org.springframework.retry.annotation.Retryable) || @within(org.springframework.retry.annotation.Retryable) ) " +
            "&& ( @annotation(org.example.proect.lavka.utils.RetryLabel) || @within(org.example.proect.lavka.utils.RetryLabel) )")
    public Object nameRetryContext(ProceedingJoinPoint pjp) throws Throwable {
        String label = resolveLabel(pjp);
        RetryContext ctx = RetrySynchronizationManager.getContext();
        if (ctx != null && label != null && !label.isBlank()) {
            ctx.setAttribute(RetryContext.NAME, label);
        }
        return pjp.proceed();
    }

    private String resolveLabel(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Class<?> targetClass = pjp.getTarget() != null ? pjp.getTarget().getClass() : method.getDeclaringClass();

        // приоритет — методовая аннотация
        RetryLabel methodAnno = AnnotationUtils.findAnnotation(method, RetryLabel.class);
        if (methodAnno != null) return methodAnno.value();

        // иначе — классовая
        RetryLabel classAnno = AnnotationUtils.findAnnotation(targetClass, RetryLabel.class);
        return classAnno != null ? classAnno.value() : null;
    }
}