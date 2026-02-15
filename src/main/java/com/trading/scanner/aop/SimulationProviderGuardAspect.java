package com.trading.scanner.aop;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class SimulationProviderGuardAspect {

    private final Environment environment;

    @Around("@within(SimulationExit) || @annotation(SimulationExit)")
    public Object guardProviderCall(ProceedingJoinPoint joinPoint) throws Throwable {
        boolean isSimulationProfile = Arrays.asList(environment.getActiveProfiles()).contains("simulation");

        if (isSimulationProfile) {
            String methodName = joinPoint.getSignature().toShortString();
            String message = String.format(
                "DETERMINISM VIOLATION: Method '%s' was called, but it is annotated with @SimulationExit. " +
                "Live external provider calls are forbidden in 'simulation' profile.",
                methodName
            );
            log.error(message);
            throw new IllegalStateException(message);
        }

        return joinPoint.proceed();
    }
}
