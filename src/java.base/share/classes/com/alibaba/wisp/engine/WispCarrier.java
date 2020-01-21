package com.alibaba.wisp.engine;

import java.dyn.Coroutine;
import java.dyn.CoroutineSupport;
import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * {@link WispCarrier} schedules all {@link WispTask} on according worker and control their life cycle
 * {@link WispCarrier} exposed its scheduling function for wisp inner usage and maintained all thread local
 * schedule info, such as current active {@link WispTask} and thread local task cache, etc.
 *
 * <p> A {@link WispCarrier} instance is expected to run in a specific worker. Get per-thread instance by calling
 * {@link WispCarrier#current()}.
 */
final class WispCarrier implements Comparable<WispCarrier> {

    private static final AtomicIntegerFieldUpdater<WispEngine> TASK_COUNT_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(WispEngine.class, "runningTaskCount");

    /**
     * The user can only can only get thread-specific carrier by calling this method.
     * <p>
     * We can not use ThreadLocal any more, because if transparentAsync, it behaves as a coroutine local.
     *
     * @return thread-specific carrier
     */
    static WispCarrier current() {
        Thread thread = WispEngine.JLA.currentThread0();
        WispTask current = WispEngine.JLA.getWispTask(thread);
        if (current == null) {
            WispCarrier carrier = new WispCarrier(WispEngine.WISP_ROOT_ENGINE);
            if (carrier.threadTask.ctx != null) {
                WispEngine.JLA.setWispTask(thread, carrier.getCurrentTask());
                carrier.init();
            } // else: fake carrier used in jni attach
            return carrier;
        } else {
            return current.carrier;
        }
    }

    // thread, threadTask and worker are 1:1:1 related
    WispScheduler.Worker worker;
    final Thread thread;
    private final WispTask threadTask;
    WispEngine engine;
    // current running task
    WispTask current;
    private List<WispTask> taskCache = new ArrayList<>();
    boolean isInCritical;
    WispCounter counter;
    int schedTick;
    int lastSchedTick; // access by Sysmon
    boolean terminated;
    private long switchTimestamp = 0;
    private WispTask yieldingTask;
    private TimeOut pendingTimer;

    private WispCarrier(WispEngine engine) {
        thread = WispEngine.JLA.currentThread0();
        this.engine = engine;
        CoroutineSupport cs = thread.getCoroutineSupport();
        current = threadTask = new WispTask(this,
                cs == null ? null : cs.threadCoroutine(),
                cs != null, true);
        if (cs == null) { // fake carrier used in jni attach
            threadTask.setThreadWrapper(thread);
        } else {
            threadTask.reset(null, null,
                    "THREAD: " + thread.getName(), thread, thread.getContextClassLoader());
        }
    }

    /**
     * Use 2nd-phase init after constructor. Because if constructor calls Thread.currentThread(),
     * and recursive calls constructor, then stackOverflow.
     */
    private void init() {
        WispTask.trackTask(threadTask);
        counter = WispCounter.create(this);
    }

    /**
     * @return Currently running WispTask. Ensured by {@link #yieldTo(WispTask)}
     * If calling in a non-coroutine environment, return a thread-emulated WispTask.
     */
    WispTask getCurrentTask() {
        return current;
    }

    /**
     * Each WispCarrier has a corresponding worker. Thread can't be changed for WispCarrier.
     * Use thread id as WispCarrier id.
     *
     * @return WispCarrier id
     */
    long getId() {
        assert thread != null;
        return thread.getId();
    }

    // ----------------------------------------------- lifecycle

    final WispTask runTaskInternal(Runnable target, String name, Thread thread, ClassLoader ctxLoader) {
        if (engine.hasBeenShutdown && !WispTask.SHUTDOWN_TASK_NAME.equals(name)) {
            throw new RejectedExecutionException("Wisp carrier has been shutdown");
        }
        boolean isInCritical0 = isInCritical;
        isInCritical = true;
        WispTask wispTask;
        try {
            counter.incrementCreateTaskCount();
            if ((wispTask = getTaskFromCache()) == null) {
                wispTask = new WispTask(this, null, true, false);
                WispTask.trackTask(wispTask);
            }
            wispTask.reset(target, current, name, thread, ctxLoader);
            TASK_COUNT_UPDATER.incrementAndGet(engine);
        } finally {
            isInCritical = isInCritical0;
        }
        yieldTo(wispTask);
        runWispTaskEpilog();

        return wispTask;
    }

    /**
     * The only exit path of a task.
     * WispTask must call {@code taskExit()} to exit safely.
     */
    void taskExit() { // and exit
        current.status = WispTask.Status.ZOMBIE;
        TASK_COUNT_UPDATER.decrementAndGet(engine);

        current.countExecutionTime(switchTimestamp);
        switchTimestamp = 0;

        unregisterEvent();
        returnTaskToCache(current);

        // reset threadWrapper after call returnTaskToCache,
        // since the threadWrapper will be used in Thread.currentThread()
        current.resetThreadWrapper();
        counter.incrementCompleteTaskCount();

        // In Tenant killing process, we have an pending exception,
        // WispTask.Coroutine's loop will be breaked
        // invoke an explicit reschedule instead of return
        schedule();
    }

    /**
     * @return task from global cached theScheduler
     */
    private WispTask getTaskFromCache() {
        assert WispCarrier.current() == this;
        if (!taskCache.isEmpty()) {
            return taskCache.remove(taskCache.size() - 1);
        }
        if (engine.hasBeenShutdown) {
            return null;
        }
        WispTask task = engine.groupTaskCache.poll();
        if (task == null) {
            return null;
        }
        if (task.carrier != this) {
            if (steal(task) != Coroutine.StealResult.SUCCESS) {
                engine.groupTaskCache.add(task);
                return null;
            }
        }
        assert task.carrier == this;
        return task;
    }

    /**
     * return task back to global cache
     */
    private void returnTaskToCache(WispTask task) {
        // reuse exited wispTasks from shutdown wispEngine is very tricky, so we'd better not return
        // these tasks to global cache
        if (taskCache.size() > WispConfiguration.WISP_ENGINE_TASK_CACHE_SIZE && !engine.hasBeenShutdown) {
            engine.groupTaskCache.add(task);
        } else {
            taskCache.add(task);
        }
    }

    /**
     * hook for yield wispTask
     */
    private void runWispTaskEpilog() {
        processPendingTimer();
        processYield();
    }

    void destroy() {
        WispTask.cleanExitedTasks(taskCache);
        WispTask.cleanExitedTask(threadTask);
        terminated = true;
    }

    // ------------------------------------------  scheduling

    /**
     * Block current coroutine and do scheduling.
     * Typically called when resource is not ready.
     */
    final void schedule() {
        assert WispCarrier.current() == this;
        WispTask current = this.current;
        current.countExecutionTime(switchTimestamp);
        WispTask parent = current.parent;
        if (parent != null) {
            assert parent.isRunnable();
            assert parent.carrier == this;
            // DESIGN:
            // only the first park of wisp should go back to parent
            current.parent = null;
            yieldTo(parent);
        } else {
            assert current.resumeEntry != null && current != threadTask
                    : "call `schedule()` in scheduler";
            current.resumeEntry.setStealEnable(true);
            yieldTo(threadTask); // letting the scheduler choose runnable task
        }
        if (engine.hasBeenShutdown && current != threadTask
                && !WispTask.SHUTDOWN_TASK_NAME.equals(current.getName())) {
            CoroutineSupport.checkAndThrowException(current.ctx);
        }
    }

    /**
     * Wake up a {@link WispTask} that belongs to this carrier
     *
     * @param task target task
     */
    void wakeupTask(WispTask task) {
        assert !task.isThreadTask();
        assert task.resumeEntry != null;
        assert task.carrier == this;
        task.updateEnqueueTime();
        engine.scheduler.executeWithWorkerThread(task.resumeEntry, thread);
    }

    /**
     * create a Entry runnable for wisp task,
     * used for bridge coroutine and Executor interface.
     */
    StealAwareRunnable createResumeEntry(WispTask task) {
        assert !task.isThreadTask();
        return new StealAwareRunnable() {
            boolean stealEnable = true;

            @Override
            public void run() {
                WispCarrier current = WispCarrier.current();
                /*
                 * Please be extremely cautious:
                 * task.carrier can not be changed here by other thread
                 * is based on our implementation of using park instead of
                 * direct schedule, so only one thread could receive
                 * this closure.
                 */
                WispCarrier source = task.carrier;
                if (source != current) {
                    Coroutine.StealResult res = current.steal(task);
                    if (res != Coroutine.StealResult.SUCCESS) {
                        if (res != Coroutine.StealResult.FAIL_BY_CONTENTION) {
                            stealEnable = false;
                        }
                        source.wakeupTask(task);
                        return;
                    }
                    // notify detached empty worker to exit
                    if (source.worker.hasBeenHandoff && TASK_COUNT_UPDATER.get(source.engine) == 0) {
                        source.worker.signal();
                    }
                }
                current.countEnqueueTime(task.getEnqueueTime());
                task.resetEnqueueTime();
                current.yieldTo(task);
                current.runWispTaskEpilog();
            }

            @Override
            public void setStealEnable(boolean b) {
                stealEnable = b;
            }

            @Override
            public boolean isStealEnable() {
                return stealEnable;
            }
        };
    }

    /**
     * Steal task from it's current bond carrier to this carrier
     *
     * @return steal result
     */
    private Coroutine.StealResult steal(WispTask task) {
        /* shutdown is an async operation in wisp2, SHUTDOWN task relies on runningTaskCount to
        determine whether it's okay to exit the worker, hence we need to make sure no more new
        wispTasks are created or stolen for hasBeenShutdown engines
        for example:
        1. SHUTDOWN task found runningTaskCount equals 0 and exit
        2. worker's task queue may still has some remaining tasks, when tried to steal these tasks
        we may encounter jvm crash.
        */
        if (engine.hasBeenShutdown) {
            return Coroutine.StealResult.FAIL_BY_STATUS;
        }

        assert WispCarrier.current() == this;
        assert !task.isThreadTask();
        if (task.carrier != this) {
            while (task.stealLock != 0) {/* wait until steal enabled */}
            assert task.parent == null;
            Coroutine.StealResult res = task.ctx.steal(true);
            if (res != Coroutine.StealResult.SUCCESS) {
                task.stealFailureCount++;
                return res;
            }
            task.stealCount++;
            task.setCarrier(this);
        }
        return Coroutine.StealResult.SUCCESS;
    }

    /**
     * The ONLY entry point to a task,
     * {@link #current} will be set correctly
     *
     * @param task coroutine to run
     */
    private boolean yieldTo(WispTask task) {
        assert task != null;
        assert WispCarrier.current() == this;
        assert task.carrier == this;
        assert task != current;

        schedTick++;

        if (task.status == WispTask.Status.ZOMBIE) {
            unregisterEvent(task);
            return false;
        }

        WispTask from = current;
        current = task;
        counter.incrementSwitchCount();
        switchTimestamp = WispEngine.getNanoTime();
        assert !isInCritical;
        WispTask.switchTo(from, task);
        // Since carrier is changed with stealing, we shouldn't directly access carrier's member any more.
        assert WispCarrier.current().current == from;
        assert !from.carrier.isInCritical;
        return true;
    }

    /**
     * Telling to the scheduler that the current carrier is willing to yield
     * its current use of a processor.
     * <p>
     * Called by {@link Thread#yield()}
     */
    void yield() {
        if (!WispConfiguration.WISP_HIGH_PRECISION_TIMER && worker != null) {
            worker.processTimer();
        }
        if (WispEngine.runningAsCoroutine(current.getThreadWrapper())) {
            if (getTaskQueueLength() > 0) {
                assert yieldingTask == null;
                yieldingTask = current;
                // delay it, make sure wakeupTask is called after yield out
                schedule();
            }
        } else {
            WispEngine.JLA.yield0();
        }
    }

    private void processYield() {
        assert current.isThreadTask();
        if (yieldingTask != null) {
            wakeupTask(yieldingTask);
            yieldingTask = null;
        }
    }

    // ------------------------------------------------ IO

    /**
     * Modify current {@link WispTask}'s interest channel and event.
     * {@see registerEvent(...)}
     * <p>
     * Used for implementing socket io
     * <pre>
     *     while (!ch.read(buf) == 0) { // 0 indicate IO not ready, not EOF..
     *         registerEvent(ch, OP_READ);
     *         schedule();
     *     }
     *     // read is done here
     * <pre/>
     */
    void registerEvent(SelectableChannel ch, int events) throws IOException {
        registerEvent(current, ch, events);
    }


    /**
     * register target {@link WispTask}'s interest channel and event.
     *
     * @param ch     the channel that is related to the current WispTask
     * @param events interest event
     * @throws IOException
     */
    private void registerEvent(WispTask target, SelectableChannel ch, int events) throws IOException {
        if (ch != null && ch.isOpen() && events != 0) {
            WispEventPump.Pool.INSTANCE.registerEvent(target, ch, events);
        }
    }

    /**
     * Clean current task's interest event before an non-IO blocking operation
     * or task exit to prevent unexpected wake up.
     */
    void unregisterEvent() {
        unregisterEvent(current);
    }

    private void unregisterEvent(WispTask target) {
        if (target.ch != null) {
            target.resetRegisterEventTime();
            target.ch = null;
        }
    }

    // ------------------------------------------------ timer support

    /**
     * Add a timer for current {@link WispTask},
     * used for implementing timed IO operation / sleep etc...
     *
     * @param deadlineNano deadline of the timer
     * @param fromJvm      synchronized or obj.wait()
     */
    void addTimer(long deadlineNano, boolean fromJvm) {
        WispTask task = current;
        TimeOut timeOut = new TimeOut(task, deadlineNano, fromJvm);
        task.timeOut = timeOut;

        if (WispConfiguration.WISP_HIGH_PRECISION_TIMER) {
            if (task.isThreadTask()) {
                scheduleInTimer(timeOut);
            } else {
                /*
                 * timer.schedule may enter park() again
                 * we delegate this operation to thread coroutine
                 * (which always use native park)
                 */
                pendingTimer = timeOut;
            }
        } else {
            engine.scheduler.addTimer(timeOut, thread);
        }
    }

    /**
     * Cancel the timer added by {@link #addTimer(long, boolean)}.
     */
    void cancelTimer() {
        if (current.timeOut != null) {
            current.timeOut.canceled = true;
            if (!WispConfiguration.WISP_HIGH_PRECISION_TIMER) {
                engine.scheduler.cancelTimer(current.timeOut, thread);
            }
            current.timeOut = null;
        }
        pendingTimer = null;
    }

    private void processPendingTimer() {
        assert current.isThreadTask();
        if (WispConfiguration.WISP_HIGH_PRECISION_TIMER && pendingTimer != null) {
            scheduleInTimer(pendingTimer);
            pendingTimer = null;
        }
    }

    private void scheduleInTimer(TimeOut timeOut) {
        boolean isInCritical0 = isInCritical;
        final long timeout = timeOut.deadlineNano - System.nanoTime();
        isInCritical = true;
        if (timeout > 0) {
            WispEngine.timer.schedule(new Runnable() {
                @Override
                public void run() {
                    if (!timeOut.canceled) {
                        timeOut.doUnpark();
                    }
                }
            }, timeout, TimeUnit.NANOSECONDS);
        } else if (!timeOut.canceled) {
            timeOut.task.jdkUnpark();
        }
        isInCritical = isInCritical0;
    }

    // ----------------------------------------------- status fetch

    /**
     * @return if current carrier is busy
     */
    boolean isRunning() {
        return current != threadTask;
    }

    /**
     * @return queue length. used for mxBean report
     */
    int getTaskQueueLength() {
        if (worker == null) {
            return 0;
        }
        int ql = worker.queueLength;
        // use a local copy to avoid queueLength change to negative.
        return ql > 0 ? ql : 0;
    }

    /**
     * @return running task number, used for mxBean report
     */
    int getRunningTaskCount() {
        return engine.runningTaskCount;
    }

    // -----------------------------------------------  retake

    /**
     * hand off wispEngine for blocking system calls.
     */
    void handOff() {
        engine.scheduler.handOffWorkerThread(thread);
    }

    // ----------------------------------------------- Monitoring

    WispCounter getCounter() {
        return counter;
    }

    void countEnqueueTime(long enqueueTime) {
        if (enqueueTime != 0) {
            counter.incrementTotalEnqueueTime(System.nanoTime() - enqueueTime);
        }
    }

    @Override
    public String toString() {
        return "WispCarrier on " + thread.getName();
    }

    @Override
    public int compareTo(WispCarrier o) {
        return Long.compare(getId(), o.getId());
    }

}
