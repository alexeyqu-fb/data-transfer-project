/*
 * Copyright 2023 The Data Transfer Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.datatransferproject.spi.transfer.idempotentexecutor;

import static java.lang.String.format;

import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.Serializable;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.datatransferproject.api.launcher.Monitor;
import org.datatransferproject.types.transfer.errors.ErrorDetail;
import org.datatransferproject.types.transfer.retry.RetryException;
import org.datatransferproject.types.transfer.retry.RetryStrategyLibrary;
import org.datatransferproject.types.transfer.retry.RetryingCallable;

/** A {@link IdempotentImportExecutor} that stores known values in memory. */
public class RetryingInMemoryIdempotentImportExecutor implements IdempotentImportExecutor {

  private final Map<String, Serializable> knownValues = new HashMap<>();
  private final Map<String, ErrorDetail> errors = new HashMap<>();
  private final Map<String, ErrorDetail> recentErrors = new HashMap<>();
  private final Monitor monitor;
  private UUID jobId;
  private final RetryStrategyLibrary retryStrategyLibrary;

  public RetryingInMemoryIdempotentImportExecutor(
      Monitor monitor, RetryStrategyLibrary  retryStrategyLibrary) {
    this.monitor = monitor;
    this.retryStrategyLibrary = retryStrategyLibrary;
  }

  @Override
  public <T extends Serializable> T executeAndSwallowIOExceptions(
      String idempotentId, String itemName, Callable<T> callable) throws Exception {
    try {
      return executeOrThrowException(idempotentId, itemName, callable);
    } catch (IOException e) {
      // Note all errors are logged in executeOrThrowException so no need to re-log them here.
      return null;
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends Serializable> T executeOrThrowException(
      String idempotentId, String itemName, Callable<T> callable) throws Exception {
    String jobIdPrefix = "Job " + jobId + ": ";

    RetryingCallable<T> retryingCallable =
        new RetryingCallable<>(
            callable,
            retryStrategyLibrary,
            Clock.systemUTC(),
            monitor);

    if (knownValues.containsKey(idempotentId)) {
      monitor.debug(
          () ->
              jobIdPrefix
                  + format("Using cached key %s from cache for %s", idempotentId, itemName));
      return (T) knownValues.get(idempotentId);
    }
    try {
      T result = retryingCallable.call();
      knownValues.put(idempotentId, result);
      monitor.debug(
          () -> jobIdPrefix + format("Storing key %s in cache for %s", idempotentId, itemName));
      errors.remove(idempotentId);
      return result;
    } catch (RetryException e) {
      ErrorDetail.Builder errorDetailBuilder = ErrorDetail.builder();
      errorDetailBuilder.setId(idempotentId)
          .setTitle(itemName)
          .setException(Throwables.getStackTraceAsString(e));
      if(e.canSkip()){
        ErrorDetail errorDetail = errorDetailBuilder.setCanSkip(true).build();
        errors.put(idempotentId, errorDetail);
        recentErrors.put(idempotentId, errorDetail);
        monitor.severe(() -> jobIdPrefix + "Problem with importing item, but skipping: " + errorDetail);
        return  null;
      } else {
        ErrorDetail errorDetail = errorDetailBuilder.build();
        errors.put(idempotentId, errorDetail);
        recentErrors.put(idempotentId, errorDetail);
        monitor.severe(() -> jobIdPrefix + "Problem with importing item, cannot be skipped: " + errorDetail);
        throw e;
      }
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends Serializable> T getCachedValue(String idempotentId) {
    if (!knownValues.containsKey(idempotentId)) {
      throw new IllegalArgumentException(
          idempotentId
              + " is not a known key, known keys: "
              + Joiner.on(", ").join(knownValues.keySet()));
    }
    return (T) knownValues.get(idempotentId);
  }

  @Override
  public boolean isKeyCached(String idempotentId) {
    return knownValues.containsKey(idempotentId);
  }

  @Override
  public Collection<ErrorDetail> getErrors() {
    return ImmutableList.copyOf(errors.values());
  }

  @Override
  public void setJobId(UUID jobId) {
    this.jobId = jobId;
  }

  @Override
  public Collection<ErrorDetail> getRecentErrors() {
    return ImmutableList.copyOf(recentErrors.values());
  }

  @Override
  public void resetRecentErrors() {
    recentErrors.clear();
  }
}
