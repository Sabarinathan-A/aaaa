package com.frauddetector.testkit;

import java.util.Objects;

/**
 * Zero-dependency assertion helpers for the hand-rolled test harness.
 *
 * <p><b>Test convention:</b> a test class name ends in {@code Test} and exposes
 * a {@code public static void main(String[] args)} entry point. The
 * {@code ./build.sh test} driver compiles {@code src/main} + {@code src/test},
 * discovers every compiled {@code *Test} class, and runs each one with
 * {@code java}. Any failed assertion throws {@link AssertionError}, which makes
 * that class's JVM exit non-zero; the driver aggregates results and exits 1 if
 * any test class failed. JUnit is unavailable in this sandbox, so this is the
 * substitute.
 */
public final class Assert {

    private Assert() {
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("assertTrue failed: " + message);
        }
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError("assertFalse failed: " + message);
        }
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("assertEquals failed: " + message
                    + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError("assertNull failed: " + message + " (actual=" + value + ")");
        }
    }

    public static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError("assertNotNull failed: " + message);
        }
    }

    /** Functional contract for a block of code expected to throw. */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Asserts that {@code runnable} throws an exception assignable to
     * {@code expectedType}. Returns the thrown exception for further checks.
     */
    public static <T extends Throwable> T assertThrows(Class<T> expectedType,
                                                        ThrowingRunnable runnable,
                                                        String message) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (expectedType.isInstance(t)) {
                return expectedType.cast(t);
            }
            throw new AssertionError("assertThrows failed: " + message
                    + " (expected " + expectedType.getName() + " but got " + t.getClass().getName() + ")");
        }
        throw new AssertionError("assertThrows failed: " + message
                + " (expected " + expectedType.getName() + " but nothing was thrown)");
    }
}
