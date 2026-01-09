/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.workspace.impl;

import org.gradle.api.internal.cache.CacheConfigurationsInternal;
import org.gradle.cache.FineGrainedCacheBuilder;
import org.gradle.cache.FineGrainedCacheCleanupStrategyFactory;
import org.gradle.cache.FineGrainedMarkAndSweepCacheCleanupStrategy;
import org.gradle.cache.FineGrainedMarkAndSweepCacheCleanupStrategy.FineGrainedCacheEntrySoftDeleter;
import org.gradle.cache.FineGrainedPersistentCache;
import org.gradle.cache.internal.ProducerGuard;
import org.gradle.internal.execution.workspace.ImmutableWorkspaceProvider;
import org.gradle.internal.file.FileAccessTimeJournal;
import org.gradle.internal.file.impl.SingleDepthFileAccessTracker;

import java.io.Closeable;
import java.io.File;
import java.util.function.Supplier;

public class CacheBasedImmutableWorkspaceProvider implements ImmutableWorkspaceProvider, Closeable {

    private final SingleDepthFileAccessTracker fileAccessTracker;
    private final FineGrainedPersistentCache cache;
    private final FineGrainedCacheEntrySoftDeleter softDeleter;
    private final ProducerGuard<String> guard;

    public static CacheBasedImmutableWorkspaceProvider createWorkspaceProvider(
        FineGrainedCacheBuilder cacheBuilder,
        FileAccessTimeJournal fileAccessTimeJournal,
        CacheConfigurationsInternal cacheConfigurations,
        FineGrainedCacheCleanupStrategyFactory cacheCleanupStrategyFactory
    ) {
        return new CacheBasedImmutableWorkspaceProvider(
            cacheBuilder,
            fileAccessTimeJournal,
            cacheConfigurations,
            cacheCleanupStrategyFactory
        );
    }

    private CacheBasedImmutableWorkspaceProvider(
        FineGrainedCacheBuilder cacheBuilder,
        FileAccessTimeJournal fileAccessTimeJournal,
        CacheConfigurationsInternal cacheConfigurations,
        FineGrainedCacheCleanupStrategyFactory cacheCleanupStrategyFactory
    ) {
        FineGrainedMarkAndSweepCacheCleanupStrategy markAndSweepCleanupStrategy = cacheCleanupStrategyFactory.markAndSweepCleanupStrategy(
            cacheConfigurations.getCreatedResources().getEntryRetentionTimestampSupplier(),
            cacheConfigurations.getCleanupFrequency()::get
        );
        FineGrainedPersistentCache cache = cacheBuilder
            .withCleanupStrategy(markAndSweepCleanupStrategy)
            .open();
        this.softDeleter = markAndSweepCleanupStrategy.getSoftDeleter(cache);
        this.cache = cache;
        this.fileAccessTracker = new SingleDepthFileAccessTracker(fileAccessTimeJournal, cache.getBaseDir(), 1);
        this.guard = ProducerGuard.adaptive();
    }

    @Override
    public ImmutableWorkspace getWorkspace(String path) {
        File workspace = new File(cache.getBaseDir(), path);
        fileAccessTracker.markAccessed(workspace);
        return new ImmutableWorkspace() {

            @Override
            public File getImmutableLocation() {
                return workspace;
            }

            @Override
            public <T> T withProcessLock(Supplier<T> action) {
                return cache.useCache(path, action);
            }

            @Override
            public <T> T withThreadLock(Supplier<T> action) {
                return guard.guardByKey(path, action);
            }

            @Override
            public boolean isSoftDeleted() {
                return softDeleter.isSoftDeleted(path);
            }

            @Override
            public void ensureUnSoftDeleted() {
                softDeleter.removeSoftDeleteMarker(path);
            }
        };
    }

    @Override
    public void close() {
        cache.close();
    }
}
