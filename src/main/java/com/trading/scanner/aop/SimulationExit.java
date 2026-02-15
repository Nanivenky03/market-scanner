package com.trading.scanner.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for methods that should not be executed when the 'simulation'
 * profile is active. This is a safeguard to prevent accidental calls to live,
 * external providers during a deterministic simulation replay.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SimulationExit {
}
