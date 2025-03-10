/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import com.google.common.annotations.VisibleForTesting;
import io.dropwizard.lifecycle.Managed;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.textsecuregcm.util.Constants;
import org.whispersystems.textsecuregcm.util.Util;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public class AccountDatabaseCrawler implements Managed, Runnable {

  private static final Logger logger = LoggerFactory.getLogger(AccountDatabaseCrawler.class);
  private static final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private static final Timer readChunkTimer = metricRegistry.timer(name(AccountDatabaseCrawler.class, "readChunk"));
  private static final Timer preReadChunkTimer = metricRegistry.timer(
      name(AccountDatabaseCrawler.class, "preReadChunk"));
  private static final Timer processChunkTimer = metricRegistry.timer(
      name(AccountDatabaseCrawler.class, "processChunk"));

  private static final long WORKER_TTL_MS = 120_000L;
  private static final long ACCELERATED_CHUNK_INTERVAL = 10L;

  private final AccountsManager accounts;
  private final int chunkSize;
  private final long chunkIntervalMs;
  private final String workerId;
  private final AccountDatabaseCrawlerCache cache;
  private final List<AccountDatabaseCrawlerListener> listeners;
  private final ExecutorService chunkPreReadExecutorService;

  private final DynamicConfigurationManager dynamicConfigurationManager;

  private AtomicBoolean running = new AtomicBoolean(false);
  private boolean finished;

  public AccountDatabaseCrawler(AccountsManager accounts,
      AccountDatabaseCrawlerCache cache,
      List<AccountDatabaseCrawlerListener> listeners,
      int chunkSize,
      long chunkIntervalMs,
      ExecutorService chunkPreReadExecutorService,
      DynamicConfigurationManager dynamicConfigurationManager) {
    this.accounts = accounts;
    this.chunkSize = chunkSize;
    this.chunkIntervalMs = chunkIntervalMs;
    this.workerId = UUID.randomUUID().toString();
    this.cache = cache;
    this.listeners = listeners;
    this.chunkPreReadExecutorService = chunkPreReadExecutorService;

    this.dynamicConfigurationManager = dynamicConfigurationManager;
  }

  @Override
  public synchronized void start() {
    running.set(true);
    new Thread(this).start();
  }

  @Override
  public synchronized void stop() {
    running.set(false);
    notifyAll();
    while (!finished) {
      Util.wait(this);
    }
  }

  @Override
  public void run() {
    boolean accelerated = false;

    while (running.get()) {
      try {
        accelerated = doPeriodicWork();
        sleepWhileRunning(accelerated ? ACCELERATED_CHUNK_INTERVAL : chunkIntervalMs);
      } catch (Throwable t) {
        logger.warn("error in database crawl: {}: {}", t.getClass().getSimpleName(), t.getMessage(), t);
        Util.sleep(10000);
      }
    }

    synchronized (this) {
      finished = true;
      notifyAll();
    }
  }

  @VisibleForTesting
  public boolean doPeriodicWork() {
    if (cache.claimActiveWork(workerId, WORKER_TTL_MS)) {

      try {
        final long startTimeMs = System.currentTimeMillis();
        processChunk();
        if (cache.isAccelerated()) {
          return true;
        }
        final long endTimeMs = System.currentTimeMillis();
        final long sleepIntervalMs = chunkIntervalMs - (endTimeMs - startTimeMs);
        if (sleepIntervalMs > 0) {
          logger.info("Sleeping {}ms", sleepIntervalMs);
          sleepWhileRunning(sleepIntervalMs);
        }
      } finally {
        cache.releaseActiveWork(workerId);
      }
    }
    return false;
  }

  private void processChunk() {

    try (Timer.Context timer = processChunkTimer.time()) {

      final boolean useDynamo = dynamicConfigurationManager.getConfiguration()
          .getAccountsDynamoDbMigrationConfiguration()
          .isDynamoCrawlerEnabled();

      final Optional<UUID> fromUuid = getLastUuid(useDynamo);

      if (fromUuid.isEmpty()) {
        logger.info("Started crawl");
        listeners.forEach(AccountDatabaseCrawlerListener::onCrawlStart);
      }

      final AccountCrawlChunk chunkAccounts = readChunk(fromUuid, chunkSize, useDynamo);

      primeDatabaseForNextChunkAsync(chunkAccounts.getLastUuid(), chunkSize, useDynamo);

      if (chunkAccounts.getAccounts().isEmpty()) {
        logger.info("Finished crawl");
        listeners.forEach(listener -> listener.onCrawlEnd(fromUuid));
        cacheLastUuid(Optional.empty(), useDynamo);
        cache.setAccelerated(false);
      } else {
        logger.info("Processing chunk");
        try {
          for (AccountDatabaseCrawlerListener listener : listeners) {
            listener.timeAndProcessCrawlChunk(fromUuid, chunkAccounts.getAccounts());
          }
          cacheLastUuid(chunkAccounts.getLastUuid(), useDynamo);
        } catch (AccountDatabaseCrawlerRestartException e) {
          cacheLastUuid(Optional.empty(), useDynamo);
          cache.setAccelerated(false);
        }
      }
    }
  }

  /**
   * This is an optimization based on the observation that cold reads of chunks are slow, but subsequent reads of the
   * same chunk (within a few minutes) are fast. We can’t easily store the actual result data, since the next chunk
   * might be processed elsewhere, but the time savings are still substantial.
   */
  private void primeDatabaseForNextChunkAsync(Optional<UUID> fromUuid, int chunkSize, boolean useDynamo) {
    if (dynamicConfigurationManager.getConfiguration().getAccountsDynamoDbMigrationConfiguration()
        .isCrawlerPreReadNextChunkEnabled()) {
      if (!useDynamo && fromUuid.isPresent()) {
        chunkPreReadExecutorService.submit(() -> readChunk(fromUuid, chunkSize, false, preReadChunkTimer));
      }
    }
  }

  private AccountCrawlChunk readChunk(Optional<UUID> fromUuid, int chunkSize, boolean useDynamo) {
    return readChunk(fromUuid, chunkSize, useDynamo, readChunkTimer);
  }

  private AccountCrawlChunk readChunk(Optional<UUID> fromUuid, int chunkSize, boolean useDynamo, Timer readTimer) {
    try (Timer.Context timer = readTimer.time()) {

      if (fromUuid.isPresent()) {
        return useDynamo
            ? accounts.getAllFromDynamo(fromUuid.get(), chunkSize)
            : accounts.getAllFrom(fromUuid.get(), chunkSize);
      }

      return useDynamo
          ? accounts.getAllFromDynamo(chunkSize)
          : accounts.getAllFrom(chunkSize);
    }
  }

  private Optional<UUID> getLastUuid(final boolean useDynamo) {
    if (useDynamo) {
      return cache.getLastUuidDynamo();
    } else {
      return cache.getLastUuid();
    }
  }

  private void cacheLastUuid(final Optional<UUID> lastUuid, final boolean useDynamo) {
    if (useDynamo) {
      cache.setLastUuidDynamo(lastUuid);
    } else {
      cache.setLastUuid(lastUuid);
    }
  }

  private synchronized void sleepWhileRunning(long delayMs) {
    if (running.get()) Util.wait(this, delayMs);
  }

}
