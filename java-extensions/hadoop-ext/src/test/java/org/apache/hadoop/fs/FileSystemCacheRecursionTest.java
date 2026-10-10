// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.hadoop.fs;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.alias.CredentialProvider;
import org.apache.hadoop.security.alias.CredentialProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class FileSystemCacheRecursionTest {
    private static final int CREATOR_COUNT = 64;
    private static final ThreadLocal<FileSystem.Cache> RECURSIVE_CACHE = new ThreadLocal<>();
    private static final AtomicInteger INITIALIZATIONS = new AtomicInteger();
    private static final AtomicInteger ACTIVE_INITIALIZATIONS = new AtomicInteger();
    private static final AtomicInteger PEAK_INITIALIZATIONS = new AtomicInteger();
    private static volatile CyclicBarrier credentialBarrier;

    public static class RecursiveFileSystem extends RawLocalFileSystem {
        @Override
        public void initialize(URI uri, Configuration conf) throws IOException {
            setConf(conf);
            INITIALIZATIONS.incrementAndGet();
            if ("same".equals(uri.getAuthority())) {
                RECURSIVE_CACHE.get().get(uri, conf);
                return;
            }
            if ("failure".equals(uri.getAuthority())) {
                throw new IOException("synthetic initializer failure");
            }

            int active = ACTIVE_INITIALIZATIONS.incrementAndGet();
            PEAK_INITIALIZATIONS.accumulateAndGet(active, Math::max);
            try {
                RECURSIVE_CACHE.get().get(URI.create("file:///"), conf);
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                ACTIVE_INITIALIZATIONS.decrementAndGet();
            }
        }
    }

    public static class CredentialFileSystem extends RawLocalFileSystem {
        @Override
        public void initialize(URI uri, Configuration conf) throws IOException {
            setConf(conf);
            try {
                credentialBarrier.await(5, TimeUnit.SECONDS);
                List<CredentialProvider> providers = CredentialProviderFactory.getProviders(conf);
                if (providers.size() != 1) {
                    throw new IOException("Expected one synthetic JCEKS provider");
                }
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    @AfterEach
    void closeFileSystems() throws IOException {
        FileSystem.closeAll();
        RECURSIVE_CACHE.remove();
    }

    @Test
    void allowsNestedCreationWithOnePermit() throws Exception {
        Configuration conf = recursiveConfiguration(1);
        FileSystem.Cache cache = new FileSystem.Cache(conf);

        FileSystem first = submit(cache, () -> cache.get(URI.create("recursive://first/"), conf));

        Assertions.assertSame(first, submit(cache, () -> cache.get(URI.create("recursive://first/"), conf)));
        Assertions.assertEquals(1, INITIALIZATIONS.get());
        cache.closeAll();
    }

    @Test
    void rejectsSameKeyRecursionAndCleansUp() throws Exception {
        Configuration conf = recursiveConfiguration(1);
        FileSystem.Cache cache = new FileSystem.Cache(conf);

        IOException error = Assertions.assertThrows(IOException.class,
                () -> submit(cache, () -> cache.get(URI.create("recursive://same/"), conf)));

        Assertions.assertEquals("Recursive filesystem initialization", error.getMessage());
        Assertions.assertEquals(1, INITIALIZATIONS.get());
        submit(cache, () -> cache.get(URI.create("recursive://after-same-key-error/"), conf));
        Assertions.assertEquals(2, INITIALIZATIONS.get());
        cache.closeAll();
    }

    @Test
    void releasesInitializationStateAfterFailure() throws Exception {
        Configuration conf = recursiveConfiguration(1);
        FileSystem.Cache cache = new FileSystem.Cache(conf);

        IOException error = Assertions.assertThrows(IOException.class,
                () -> submit(cache, () -> cache.get(URI.create("recursive://failure/"), conf)));

        Assertions.assertEquals("synthetic initializer failure", error.getMessage());
        submit(cache, () -> cache.get(URI.create("recursive://after-failure/"), conf));
        Assertions.assertEquals(2, INITIALIZATIONS.get());
        cache.closeAll();
    }

    @Test
    void preservesTopLevelCreationLimit() throws Exception {
        Configuration conf = recursiveConfiguration(1);
        FileSystem.Cache cache = new FileSystem.Cache(conf);
        ExecutorService executor = newDaemonExecutor(8);
        try {
            List<Future<FileSystem>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    RECURSIVE_CACHE.set(cache);
                    try {
                        return cache.get(URI.create("recursive://parallel-" + index + "/"), conf);
                    } finally {
                        RECURSIVE_CACHE.remove();
                    }
                }));
            }
            for (Future<FileSystem> future : futures) {
                future.get(3, TimeUnit.SECONDS);
            }
            Assertions.assertEquals(1, PEAK_INITIALIZATIONS.get());
        } finally {
            executor.shutdownNow();
            cache.closeAll();
        }
    }

    @Test
    void initializesJceksProvidersWhenAllDefaultPermitsAreTaken() throws Exception {
        FileSystem.closeAll();
        credentialBarrier = new CyclicBarrier(CREATOR_COUNT);
        Configuration conf = new Configuration(false);
        conf.setClass("fs.credentialprobe.impl", CredentialFileSystem.class, FileSystem.class);
        conf.set("hadoop.security.credential.provider.path",
                "jceks://file/tmp/starrocks-cache-recursion-test.jceks");

        ExecutorService executor = newDaemonExecutor(CREATOR_COUNT);
        try {
            List<Future<FileSystem>> futures = new ArrayList<>();
            for (int i = 0; i < CREATOR_COUNT; i++) {
                int index = i;
                futures.add(executor.submit(
                        () -> FileSystem.get(URI.create("credentialprobe://node-" + index + "/"), conf)));
            }
            for (Future<FileSystem> future : futures) {
                Assertions.assertNotNull(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            FileSystem.closeAll();
        }
    }

    private Configuration recursiveConfiguration(int permits) {
        INITIALIZATIONS.set(0);
        ACTIVE_INITIALIZATIONS.set(0);
        PEAK_INITIALIZATIONS.set(0);

        Configuration conf = new Configuration(false);
        conf.setInt("fs.creation.parallel.count", permits);
        conf.setClass("fs.recursive.impl", RecursiveFileSystem.class, FileSystem.class);
        conf.setClass("fs.file.impl", RawLocalFileSystem.class, FileSystem.class);
        return conf;
    }

    private FileSystem submit(FileSystem.Cache cache, Callable<FileSystem> callable) throws Exception {
        ExecutorService executor = newDaemonExecutor(1);
        try {
            Future<FileSystem> future = executor.submit(() -> {
                RECURSIVE_CACHE.set(cache);
                try {
                    return callable.call();
                } finally {
                    RECURSIVE_CACHE.remove();
                }
            });
            try {
                return future.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                throw e;
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private ExecutorService newDaemonExecutor(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "file-system-cache-recursion-test");
            thread.setDaemon(true);
            return thread;
        });
    }
}
