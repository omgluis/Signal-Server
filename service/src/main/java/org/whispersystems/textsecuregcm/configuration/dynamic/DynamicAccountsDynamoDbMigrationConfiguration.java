package org.whispersystems.textsecuregcm.configuration.dynamic;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;

public class DynamicAccountsDynamoDbMigrationConfiguration {

  @JsonProperty
  boolean backgroundMigrationEnabled;

  @JsonProperty
  int backgroundMigrationExecutorThreads = 1;

  @JsonProperty
  boolean deleteEnabled;

  @JsonProperty
  boolean writeEnabled;

  @JsonProperty
  boolean readEnabled;

  @JsonProperty
  boolean logMismatches;

  @JsonProperty
  boolean crawlerPreReadNextChunkEnabled;

  @JsonProperty
  boolean dynamoCrawlerEnabled;

  @JsonProperty
  int dynamoCrawlerScanPageSize = 10;

  public boolean isBackgroundMigrationEnabled() {
    return backgroundMigrationEnabled;
  }

  public int getBackgroundMigrationExecutorThreads() {
    return backgroundMigrationExecutorThreads;
  }

  @VisibleForTesting
  public void setBackgroundMigrationEnabled(boolean backgroundMigrationEnabled) {
    this.backgroundMigrationEnabled = backgroundMigrationEnabled;
  }

  public void setDeleteEnabled(boolean deleteEnabled) {
    this.deleteEnabled = deleteEnabled;
  }

  public boolean isDeleteEnabled() {
    return deleteEnabled;
  }

  public void setWriteEnabled(boolean writeEnabled) {
    this.writeEnabled = writeEnabled;
  }

  public boolean isWriteEnabled() {
    return writeEnabled;
  }

  @VisibleForTesting
  public void setReadEnabled(boolean readEnabled) {
    this.readEnabled = readEnabled;
  }

  public boolean isReadEnabled() {
    return readEnabled;
  }

  public boolean isLogMismatches() {
    return logMismatches;
  }

  public boolean isCrawlerPreReadNextChunkEnabled() {
    return crawlerPreReadNextChunkEnabled;
  }

  public boolean isDynamoCrawlerEnabled() {
    return dynamoCrawlerEnabled;
  }

  public int getDynamoCrawlerScanPageSize() {
    return dynamoCrawlerScanPageSize;
  }

  @VisibleForTesting
  public void setLogMismatches(boolean logMismatches) {
    this.logMismatches = logMismatches;
  }

  @VisibleForTesting
  public void setBackgroundMigrationExecutorThreads(int threads) {
    this.backgroundMigrationExecutorThreads = threads;
  }
}
