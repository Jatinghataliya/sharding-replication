package com.example.sharding.aspect;

import com.example.sharding.context.ShardContextHolder;
import com.example.sharding.context.ShardContextHolder.Role;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

/**
 * AOP aspect that intercepts every {@code @Transactional} method and
 * automatically sets the read/write role in {@link ShardContextHolder}:
 *
 * <ul>
 *   <li>{@code @Transactional(readOnly = true)}  → {@link Role#REPLICA}</li>
 *   <li>{@code @Transactional}                   → {@link Role#PRIMARY}</li>
 * </ul>
 *
 * <p>This aspect must run <em>before</em> Spring's transaction interceptor
 * (which opens the connection) so that the DataSource routing key is set
 * before Hibernate acquires a connection. {@code @Order(1)} ensures this.</p>
 */
@Aspect
@Component
@Order(1)
public class TransactionRoutingAspect {

    private static final Logger log = LoggerFactory.getLogger(TransactionRoutingAspect.class);

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object routeToCorrectDataSource(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Transactional transactional = method.getAnnotation(Transactional.class);

        boolean isReadOnly = transactional != null && transactional.readOnly();
        Role role = isReadOnly ? Role.REPLICA : Role.PRIMARY;

        ShardContextHolder.setRole(role);
        log.debug("AOP routing → method='{}', readOnly={}, role={}",
                method.getName(), isReadOnly, role);

        try {
            return joinPoint.proceed();
        } finally {
            // Role is cleared here; shard index is cleared by the service
            ShardContextHolder.clear();
        }
    }
}
