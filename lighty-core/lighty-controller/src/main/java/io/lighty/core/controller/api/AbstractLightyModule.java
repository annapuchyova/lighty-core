/*
 * Copyright (c) 2018 Pantheon Technologies s.r.o. All Rights Reserved.
 *
 * This Source Code Form is subject to the terms of the lighty.io-core
 * Fair License 5, version 0.9.1. You may obtain a copy of the License
 * at: https://github.com/PantheonTechnologies/lighty-core/LICENSE.md
 */
package io.lighty.core.controller.api;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This abstract class implement {@link LightyModule} interface with
 * synchronization of {@link LightyModule#start()},
 * {@link LightyModule#startBlocking()} and {@link LightyModule#shutdown()}
 * methods. Users who don't want to implement their own synchronization
 * can extend this class and provide just
 * {@link AbstractLightyModule#initProcedure()} and
 * {@link AbstractLightyModule#stopProcedure()} methods.These methods
 * will be then automatically called in
 * {@link AbstractLightyModule#start()},
 * {@link AbstractLightyModule#startBlocking()} and
 * {@link AbstractLightyModule#shutdown()} methods.
 * <p>
 * <b>Example usage:</b>
 * <pre>
 * <code>
 *     public class MyLightyModule extends AbstractLightyModule {
 *         private SomeBean someBean;
 *         ...
 *        {@literal @}Override
 *         protected boolean initProcedure() {
 *             this.someBean = new SomeBean();
 *             boolean success = this.someBean.init();
 *             return success;
 *         }
 *
 *        {@literal @}Override
 *         protected boolean stopProcedure() {
 *             boolean stopSuccess = this.someBean.stop();
 *             this.someBean = null;
 *             return stopSuccess;
 *         }
 *     }
 * </code>
 * </pre>
 *
 * @author andrej.zan
 */
public abstract class AbstractLightyModule implements LightyModule {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractLightyModule.class);
    private final CountDownLatch shutdownLatch;
    private ListeningExecutorService executorService;
    private boolean executorIsProvided;
    private volatile boolean running;

    public AbstractLightyModule(ExecutorService executorService) {
        if (executorService == null) {
            this.executorIsProvided = false;
            LOG.debug("ExecutorService for LightyModule {} was not provided. By default single thread ExecutorService" +
                    " will be used.", this.getClass().getSimpleName());
        } else {
            this.executorService = MoreExecutors.listeningDecorator(executorService);
            this.executorIsProvided = true;
        }
        this.shutdownLatch = new CountDownLatch(1);
        this.running = false;
    }

    public AbstractLightyModule() {
        this(null);
    }

    /**
     * This method is called in {@link AbstractLightyModule#start()} method.
     * Implementation of this method should initialize everything necessary.
     * @return success of initialization
     */
    protected abstract boolean initProcedure();

    /**
     * This method is called in {@link AbstractLightyModule#shutdown()} method.
     * Implementation of this method should do everything necessary to
     * shutdown correctly (e.g. stop initialized beans, release resources, ...).
     * @return success of stop.
     */
    protected abstract boolean stopProcedure();

    @Override
    public synchronized ListenableFuture<Boolean> start() {
        if (this.running) {
            LOG.warn("LightyModule {} is already started.", this.getClass().getSimpleName());
            return Futures.immediateFuture(true);
        }

        if (this.executorService == null) {
            LOG.debug("Creating default single thread ExecutorService for LightyModule {}.",
                    this.getClass().getSimpleName());
            this.executorService = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        }

        LOG.info("Submitted start of LightyModule {}.", this.getClass().getSimpleName());
        return this.executorService.submit(() -> {
            synchronized (this) {
                LOG.debug("Starting initialization of LightyModule {}", this.getClass().getSimpleName());
                boolean initResult = initProcedure();
                this.running = true;
                LOG.info("LightyModule {} started.", this.getClass().getSimpleName());
                return initResult;
            }
        });
    }

    @Override
    public void startBlocking() throws InterruptedException {
        LOG.info("Start LightyModule {} and block until it will shutdown.", this.getClass().getSimpleName());
        start();
        LOG.debug("Waiting for LightyModule {} shutdown.", this.getClass().getSimpleName());
        this.shutdownLatch.await();
        LOG.info("LightyModule {} shutdown complete. Stop blocking.", this.getClass().getSimpleName());
    }

    /**
     * Start and block until shutdown is requested.
     * @param initFinishCallback callback that will be called after start is completed.
     * @throws InterruptedException
     *   thrown in case module initialization fails.
     */
    public void startBlocking(Consumer<Boolean> initFinishCallback) throws InterruptedException {
        Futures.addCallback(start(), new FutureCallback<Boolean>() {
            @Override
            public void onSuccess(@Nullable Boolean result) {
                initFinishCallback.accept(true);
            }

            @Override
            public void onFailure(Throwable t) {
                initFinishCallback.accept(false);
            }
        }, MoreExecutors.directExecutor());
        this.shutdownLatch.await();
    }

    @Override
    public synchronized ListenableFuture<Boolean> shutdown() {
        if (! this.running) {
            LOG.warn("LightyModule {} is already shut down.", this.getClass().getSimpleName());
            return Futures.immediateFuture(true);
        }
        LOG.info("Submitted shutdown of LightyModule {}.", this.getClass().getSimpleName());
        ListenableFuture<Boolean> shutdownFuture = this.executorService.submit(() -> {
            synchronized (this) {
                LOG.debug("Starting shutdown procedure of LightyModule {}.", this.getClass().getSimpleName());
                boolean stopResult = stopProcedure();
                this.shutdownLatch.countDown();
                this.running = false;
                LOG.info("LightyModule {} shutdown complete.", this.getClass().getSimpleName());
                return stopResult;
            }
        });

        if (! this.executorIsProvided) {
            return Futures.transform(shutdownFuture, (result) -> {
                LOG.debug("Shutdown default ExecutorService of LightyModule {}.", this.getClass().getSimpleName());
                this.executorService.shutdown();
                this.executorService = null;
                return true;
            }, MoreExecutors.directExecutor());
        }

        return shutdownFuture;
    }
}
