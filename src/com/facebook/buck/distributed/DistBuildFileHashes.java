/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.thrift.BuildJobStateFileHashEntry;
import com.facebook.buck.distributed.thrift.BuildJobStateFileHashes;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.rules.ActionGraph;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyFieldLoader;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.ProjectFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Responsible for extracting file hash and {@link RuleKey} information from the {@link ActionGraph}
 * and presenting it as a Thrift data structure.
 */
public class DistBuildFileHashes {
  // Map<CellIndex, BuildJobStateFileHashes>.
  private final Map<Integer, BuildJobStateFileHashes> remoteFileHashes;
  private final LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory> ruleKeyFactories;

  private final ListenableFuture<ImmutableList<BuildJobStateFileHashes>> fileHashes;
  private final ListenableFuture<ImmutableMap<BuildRule, RuleKey>>
      ruleKeys;

  public DistBuildFileHashes(
      ActionGraph actionGraph,
      SourcePathResolver sourcePathResolver,
      SourcePathRuleFinder ruleFinder,
      StackedFileHashCache originalHashCache,
      Function<? super Path, Integer> cellIndexer,
      ListeningExecutorService executorService,
      int keySeed,
      final Cell rootCell) {

    this.remoteFileHashes = Maps.newHashMap();

    StackedFileHashCache recordingHashCache = originalHashCache.newDecoratedFileHashCache(
        originalCache -> {
          Path fsRootPath = originalCache.getFilesystem().getRootPath();
          return new RecordingProjectFileHashCache(
            originalCache,
            getRemoteFileHashes(cellIndexer.apply(fsRootPath)),
            new DistBuildConfig(rootCell.getBuckConfig()),
            !rootCell.getKnownRoots().contains(fsRootPath));
        });

    this.ruleKeyFactories =

        createRuleKeyFactories(
            sourcePathResolver,
            ruleFinder,
            recordingHashCache,
            keySeed);
    this.ruleKeys =

        ruleKeyComputation(actionGraph, this.ruleKeyFactories, executorService);
    this.fileHashes =

        fileHashesComputation(
            Futures.transform(this.ruleKeys, Functions.constant(null)),
            ImmutableList.copyOf(this.remoteFileHashes.values()),
            executorService);
  }

  public BuildJobStateFileHashes getRemoteFileHashes(Integer cellIndex) {
    if (!remoteFileHashes.containsKey(cellIndex)) {
      BuildJobStateFileHashes fileHashes = new BuildJobStateFileHashes();
      fileHashes.setCellIndex(cellIndex);
      remoteFileHashes.put(cellIndex, fileHashes);
    }

    return remoteFileHashes.get(cellIndex);
  }

  public static LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory>
  createRuleKeyFactories(
      final SourcePathResolver sourcePathResolver,
      final SourcePathRuleFinder ruleFinder,
      final FileHashCache fileHashCache,
      final int keySeed) {

    return CacheBuilder.newBuilder().build(
        new CacheLoader<ProjectFilesystem, DefaultRuleKeyFactory>() {
          @Override
          public DefaultRuleKeyFactory load(ProjectFilesystem key) throws Exception {
            return new DefaultRuleKeyFactory(
                new RuleKeyFieldLoader(keySeed),
                fileHashCache,
                sourcePathResolver,
                ruleFinder
            );
          }
        });
  }

  private static ListenableFuture<ImmutableMap<BuildRule, RuleKey>> ruleKeyComputation(
      ActionGraph actionGraph,
      final LoadingCache<ProjectFilesystem, DefaultRuleKeyFactory> ruleKeyFactories,
      ListeningExecutorService executorService) {
    List<ListenableFuture<Map.Entry<BuildRule, RuleKey>>> ruleKeyEntries = new ArrayList<>();
    for (final BuildRule rule : actionGraph.getNodes()) {
      ruleKeyEntries.add(
          executorService.submit(
              () -> Maps.immutableEntry(
                  rule,
                  ruleKeyFactories.get(rule.getProjectFilesystem()).build(rule))));
    }
    ListenableFuture<List<Map.Entry<BuildRule, RuleKey>>> ruleKeyComputation =
        Futures.allAsList(ruleKeyEntries);
    return Futures.transform(
        ruleKeyComputation,
        new Function<List<Map.Entry<BuildRule, RuleKey>>, ImmutableMap<BuildRule, RuleKey>>() {
          @Override
          public ImmutableMap<BuildRule, RuleKey> apply(List<Map.Entry<BuildRule, RuleKey>> input) {
            return ImmutableMap.copyOf(input);
          }
        },
        executorService);
  }

  private static ListenableFuture<ImmutableList<BuildJobStateFileHashes>> fileHashesComputation(
      ListenableFuture<Void> ruleKeyComputationForSideEffect,
      final ImmutableList<BuildJobStateFileHashes> remoteFileHashes,
      ListeningExecutorService executorService) {
    return Futures.transform(
        ruleKeyComputationForSideEffect,
        new Function<Void, ImmutableList<BuildJobStateFileHashes>>() {
          @Override
          public ImmutableList<BuildJobStateFileHashes> apply(Void input) {
            return ImmutableList.copyOf(remoteFileHashes);
          }
        },
        executorService);
  }

  public List<BuildJobStateFileHashes> getFileHashes()
      throws IOException, InterruptedException {
    try {
      return fileHashes.get();
    } catch (ExecutionException e) {
      Throwables.throwIfInstanceOf(e.getCause(), IOException.class);
      Throwables.throwIfInstanceOf(e.getCause(), InterruptedException.class);
      throw new RuntimeException(e.getCause());
    }
  }

  /**
   * Creates a {@link FileHashCache} that returns the hash codes cached on the remote end.
   *
   * @param projectFilesystem filesystem in which the new cache will be rooted. The serialized state
   *                          only contains relative path, therefore this is needed to indicate
   *                          where on the local machine we wish to transplant the files from the
   *                          remote to.
   * @param remoteFileHashes  the serialized state.
   * @return the cache.
   */
  public static ProjectFileHashCache createFileHashCache(
      ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    return new RemoteStateBasedFileHashCache(projectFilesystem, remoteFileHashes);
  }

  public static ImmutableMap<Path, BuildJobStateFileHashEntry> indexEntriesByPath(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    if (!remoteFileHashes.isSetEntries()) {
      return ImmutableMap.of();
    }
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(input -> !input.isPathIsAbsolute() && !input.isSetArchiveMemberPath())
        .uniqueIndex(
            input -> projectFilesystem.resolve(
                MorePaths.pathWithPlatformSeparators(input.getPath().getPath())));
  }

  public static ImmutableMap<ArchiveMemberPath, BuildJobStateFileHashEntry>
  indexEntriesByArchivePath(
      final ProjectFilesystem projectFilesystem,
      BuildJobStateFileHashes remoteFileHashes) {
    if (!remoteFileHashes.isSetEntries()) {
      return ImmutableMap.of();
    }
    return FluentIterable.from(remoteFileHashes.entries)
        .filter(input -> !input.isPathIsAbsolute() && input.isSetArchiveMemberPath())
        .uniqueIndex(
            input -> ArchiveMemberPath.of(
                projectFilesystem.resolve(
                    MorePaths.pathWithPlatformSeparators(input.getPath().getPath())),
                Paths.get(input.getArchiveMemberPath())
            ));
  }
}
