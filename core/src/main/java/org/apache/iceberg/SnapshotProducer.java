/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.util.Exceptions;
import org.apache.iceberg.util.Tasks;
import org.apache.iceberg.util.ThreadPools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MAX_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS;
import static org.apache.iceberg.TableProperties.COMMIT_MIN_RETRY_WAIT_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES;
import static org.apache.iceberg.TableProperties.COMMIT_NUM_RETRIES_DEFAULT;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS;
import static org.apache.iceberg.TableProperties.COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.MANIFEST_LISTS_ENABLED;
import static org.apache.iceberg.TableProperties.MANIFEST_LISTS_ENABLED_DEFAULT;

abstract class SnapshotProducer<ThisT> implements SnapshotUpdate<ThisT> {
  private static final Logger LOG = LoggerFactory.getLogger(SnapshotProducer.class);
  static final Set<ManifestFile> EMPTY_SET = Sets.newHashSet();

  /**
   * Default callback used to delete files.
   */
  private final Consumer<String> defaultDelete = new Consumer<String>() {
    @Override
    public void accept(String file) {
      ops.io().deleteFile(file);
    }
  };

  /**
   * Cache used to enrich ManifestFile instances that are written to a ManifestListWriter.
   */
  private final LoadingCache<ManifestFile, ManifestFile> manifestsWithMetadata;

  private final TableOperations ops;
  private final String commitUUID = UUID.randomUUID().toString();
  private final AtomicInteger attempt = new AtomicInteger(0);
  private final List<String> manifestLists = Lists.newArrayList();
  private Long snapshotId = null;
  private TableMetadata base = null;
  private boolean stageOnly = false;
  private Consumer<String> deleteFunc = defaultDelete;

  protected SnapshotProducer(TableOperations ops) {
    this.ops = ops;
    this.base = ops.current();
    this.manifestsWithMetadata = Caffeine
      .newBuilder()
      .build(file -> {
        if (file.snapshotId() != null) {
          return file;
        }
        return addMetadata(ops, file);
      });
  }

  protected abstract ThisT self();

  @Override
  public ThisT stageOnly() {
    this.stageOnly = true;
    return self();
  }

  @Override
  public ThisT deleteWith(Consumer<String> deleteCallback) {
    Preconditions.checkArgument(this.deleteFunc == defaultDelete, "Cannot set delete callback more than once");
    this.deleteFunc = deleteCallback;
    return self();
  }

  /**
   * Clean up any uncommitted manifests that were created.
   * <p>
   * Manifests may not be committed if apply is called more because a commit conflict has occurred.
   * Implementations may keep around manifests because the same changes will be made by both apply
   * calls. This method instructs the implementation to clean up those manifests and passes the
   * paths of the manifests that were actually committed.
   *
   * @param committed a set of manifest paths that were actually committed
   */
  protected abstract void cleanUncommitted(Set<ManifestFile> committed);

  /**
   * A string that describes the action that produced the new snapshot.
   *
   * @return a string operation
   */
  protected abstract String operation();

  /**
   * Apply the update's changes to the base table metadata and return the new manifest list.
   *
   * @param metadataToUpdate the base table metadata to apply changes to
   * @return a manifest list for the new snapshot.
   */
  protected abstract List<ManifestFile> apply(TableMetadata metadataToUpdate);

  @Override
  public Snapshot apply() {
    this.base = ops.refresh();
    Long parentSnapshotId = base.currentSnapshot() != null ?
        base.currentSnapshot().snapshotId() : null;

    List<ManifestFile> manifests = apply(base);

    if (base.propertyAsBoolean(MANIFEST_LISTS_ENABLED, MANIFEST_LISTS_ENABLED_DEFAULT)) {
      OutputFile manifestList = manifestListPath();

      try (ManifestListWriter writer = new ManifestListWriter(
          manifestList, snapshotId(), parentSnapshotId)) {

        // keep track of the manifest lists created
        manifestLists.add(manifestList.location());

        ManifestFile[] manifestFiles = new ManifestFile[manifests.size()];

        Tasks.range(manifestFiles.length)
            .stopOnFailure().throwFailureWhenFinished()
            .executeWith(ThreadPools.getWorkerPool())
            .run(index ->
                manifestFiles[index] = manifestsWithMetadata.get(manifests.get(index)));

        writer.addAll(Arrays.asList(manifestFiles));

      } catch (IOException e) {
        throw new RuntimeIOException(e, "Failed to write manifest list file");
      }

      return new BaseSnapshot(ops,
          snapshotId(), parentSnapshotId, System.currentTimeMillis(), operation(), summary(base),
          ops.io().newInputFile(manifestList.location()));

    } else {
      return new BaseSnapshot(ops,
          snapshotId(), parentSnapshotId, System.currentTimeMillis(), operation(), summary(base),
          manifests);
    }
  }

  protected abstract Map<String, String> summary();

  /**
   * Returns the snapshot summary from the implementation and updates totals.
   */
  private Map<String, String> summary(TableMetadata previous) {
    Map<String, String> summary = summary();

    if (summary == null) {
      return ImmutableMap.of();
    }

    Map<String, String> previousSummary;
    if (previous.currentSnapshot() != null) {
      if (previous.currentSnapshot().summary() != null) {
        previousSummary = previous.currentSnapshot().summary();
      } else {
        // previous snapshot had no summary, use an empty summary
        previousSummary = ImmutableMap.of();
      }
    } else {
      // if there was no previous snapshot, default the summary to start totals at 0
      previousSummary = ImmutableMap.of(
          SnapshotSummary.TOTAL_RECORDS_PROP, "0",
          SnapshotSummary.TOTAL_FILES_PROP, "0");
    }

    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    // copy all summary properties from the implementation
    builder.putAll(summary);

    updateTotal(
        builder, previousSummary, SnapshotSummary.TOTAL_RECORDS_PROP,
        summary, SnapshotSummary.ADDED_RECORDS_PROP, SnapshotSummary.DELETED_RECORDS_PROP);
    updateTotal(
        builder, previousSummary, SnapshotSummary.TOTAL_FILES_PROP,
        summary, SnapshotSummary.ADDED_FILES_PROP, SnapshotSummary.DELETED_FILES_PROP);

    return builder.build();
  }

  @Override
  public void commit() {
    // this is always set to the latest commit attempt's snapshot id.
    AtomicLong newSnapshotId = new AtomicLong(-1L);
    try {
      Tasks.foreach(ops)
          .retry(base.propertyAsInt(COMMIT_NUM_RETRIES, COMMIT_NUM_RETRIES_DEFAULT))
          .exponentialBackoff(
              base.propertyAsInt(COMMIT_MIN_RETRY_WAIT_MS, COMMIT_MIN_RETRY_WAIT_MS_DEFAULT),
              base.propertyAsInt(COMMIT_MAX_RETRY_WAIT_MS, COMMIT_MAX_RETRY_WAIT_MS_DEFAULT),
              base.propertyAsInt(COMMIT_TOTAL_RETRY_TIME_MS, COMMIT_TOTAL_RETRY_TIME_MS_DEFAULT),
              2.0 /* exponential */)
          .onlyRetryOn(CommitFailedException.class)
          .run(taskOps -> {
            Snapshot newSnapshot = apply();
            newSnapshotId.set(newSnapshot.snapshotId());
            TableMetadata updated;
            if (stageOnly) {
              updated = base.addStagedSnapshot(newSnapshot);
            } else {
              updated = base.replaceCurrentSnapshot(newSnapshot);
            }

            // if the table UUID is missing, add it here. the UUID will be re-created each time this operation retries
            // to ensure that if a concurrent operation assigns the UUID, this operation will not fail.
            taskOps.commit(base, updated.withUUID());
          });

    } catch (RuntimeException e) {
      Exceptions.suppressAndThrow(e, this::cleanAll);
    }

    LOG.info("Committed snapshot {} ({})", newSnapshotId.get(), getClass().getSimpleName());

    try {
      // at this point, the commit must have succeeded. after a refresh, the snapshot is loaded by
      // id in case another commit was added between this commit and the refresh.
      Snapshot saved = ops.refresh().snapshot(newSnapshotId.get());
      if (saved != null) {
        cleanUncommitted(Sets.newHashSet(saved.manifests()));
        // also clean up unused manifest lists created by multiple attempts
        for (String manifestList : manifestLists) {
          if (!saved.manifestListLocation().equals(manifestList)) {
            deleteFile(manifestList);
          }
        }
      } else {
        // saved may not be present if the latest metadata couldn't be loaded due to eventual
        // consistency problems in refresh. in that case, don't clean up.
        LOG.info("Failed to load committed snapshot, skipping manifest clean-up");
      }

    } catch (RuntimeException e) {
      LOG.info("Failed to load committed table metadata, skipping manifest clean-up", e);
    }
  }

  protected void cleanAll() {
    for (String manifestList : manifestLists) {
      deleteFile(manifestList);
    }
    manifestLists.clear();
    cleanUncommitted(EMPTY_SET);
  }

  protected void deleteFile(String path) {
    deleteFunc.accept(path);
  }

  protected OutputFile manifestListPath() {
    return ops.io().newOutputFile(ops.metadataFileLocation(FileFormat.AVRO.addExtension(
        String.format("snap-%d-%d-%s", snapshotId(), attempt.incrementAndGet(), commitUUID))));
  }

  protected OutputFile manifestPath(int manifestNumber) {
    return ops.io().newOutputFile(
        ops.metadataFileLocation(FileFormat.AVRO.addExtension(commitUUID + "-m" + manifestNumber)));
  }

  protected long snapshotId() {
    if (snapshotId == null) {
      this.snapshotId = ops.newSnapshotId();
    }
    return snapshotId;
  }

  private static ManifestFile addMetadata(TableOperations ops, ManifestFile manifest) {
    try (ManifestReader reader = ManifestReader.read(
        ops.io().newInputFile(manifest.path()), ops.current()::spec)) {
      PartitionSummary stats = new PartitionSummary(ops.current().spec(manifest.partitionSpecId()));
      int addedFiles = 0;
      int existingFiles = 0;
      int deletedFiles = 0;

      Long snapshotId = null;
      long maxSnapshotId = Long.MIN_VALUE;
      for (ManifestEntry entry : reader.entries()) {
        if (entry.snapshotId() > maxSnapshotId) {
          maxSnapshotId = entry.snapshotId();
        }

        switch (entry.status()) {
          case ADDED:
            addedFiles += 1;
            if (snapshotId == null) {
              snapshotId = entry.snapshotId();
            }
            break;
          case EXISTING:
            existingFiles += 1;
            break;
          case DELETED:
            deletedFiles += 1;
            if (snapshotId == null) {
              snapshotId = entry.snapshotId();
            }
            break;
        }

        stats.update(entry.file().partition());
      }

      if (snapshotId == null) {
        // if no files were added or deleted, use the largest snapshot ID in the manifest
        snapshotId = maxSnapshotId;
      }

      return new GenericManifestFile(manifest.path(), manifest.length(), manifest.partitionSpecId(),
          snapshotId, addedFiles, existingFiles, deletedFiles, stats.summaries());

    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to read manifest: %s", manifest.path());
    }
  }

  private static void updateTotal(ImmutableMap.Builder<String, String> summaryBuilder,
                                  Map<String, String> previousSummary, String totalProperty,
                                  Map<String, String> currentSummary,
                                  String addedProperty, String deletedProperty) {
    String totalStr = previousSummary.get(totalProperty);
    if (totalStr != null) {
      try {
        long newTotal = Long.parseLong(totalStr);

        String addedStr = currentSummary.get(addedProperty);
        if (addedStr != null) {
          newTotal += Long.parseLong(addedStr);
        }

        String deletedStr = currentSummary.get(deletedProperty);
        if (deletedStr != null) {
          newTotal -= Long.parseLong(deletedStr);
        }

        summaryBuilder.put(totalProperty, String.valueOf(newTotal));

      } catch (NumberFormatException e) {
        // ignore and do not add total
      }
    }
  }
}
