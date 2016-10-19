package com.spotify.styx;

import com.google.common.base.Throwables;

import com.spotify.styx.util.Time;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A decorator for a supplier function that will cache the returned value during some
 * configurable time.
 */
public class CachedSupplier<T> implements Supplier<T> {

  private static final Logger LOG = LoggerFactory.getLogger(CachedSupplier.class);
  private static final long DEFAULT_TIMEOUT_MILLIS = 30_000;

  private final ThrowingSupplier<T, Exception> delegate;
  private final Time time;
  private final long timeoutMillis;

  private final AtomicReference<T> cachedValue = new AtomicReference<>();
  private volatile long cacheTime;

  public CachedSupplier(ThrowingSupplier<T, Exception> delegate, Time time) {
    this(delegate, time, DEFAULT_TIMEOUT_MILLIS);
  }

  public CachedSupplier(ThrowingSupplier<T, Exception> delegate, Time time, long timeoutMillis) {
    this.delegate = Objects.requireNonNull(delegate);
    this.time = Objects.requireNonNull(time);
    this.timeoutMillis = timeoutMillis;

    cacheTime = time.get().toEpochMilli();
  }

  @Override
  public T get() {
    T value = cachedValue.get();

    // does not have to guarantee synchronous update, we rely on the atomic reference
    if (value == null || timedOut()) {
      try {
        final T newValue = delegate.get();
        cachedValue.set(newValue);
        cacheTime = time.get().toEpochMilli();
        value = newValue;
      } catch (Throwable e) {
        if (value == null) {
          throw Throwables.propagate(e);
        } else {
          LOG.warn("Failed to update from delegate supplier, using old value");
        }
      }
    }

    return value;
  }

  private boolean timedOut() {
    return time.get().toEpochMilli() - cacheTime > timeoutMillis;
  }

  @FunctionalInterface
  interface ThrowingSupplier<T, E extends Exception> {
    T get() throws E;
  }
}