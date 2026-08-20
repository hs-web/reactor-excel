package org.hswebframework.reactor.excel;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;

/**
 * Selects the scheduler used for blocking work in reactive writer adapters.
 *
 * <p>The scheduler must execute tasks on threads that Reactor does not mark as non-blocking. The
 * caller owns a supplied scheduler and must keep it alive until the write publisher terminates;
 * writers never dispose it. Native non-blocking writers may ignore this option.</p>
 *
 * @since 1.0.7
 */
public final class BlockingSchedulerOption implements ExcelOption {

    private final Scheduler scheduler;

    private BlockingSchedulerOption(Scheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Use a caller-owned scheduler for blocking writer work.
     *
     * @param scheduler blocking-capable scheduler
     * @return scheduler option
     */
    public static BlockingSchedulerOption of(Scheduler scheduler) {
        return new BlockingSchedulerOption(scheduler);
    }

    /**
     * Resolve the last configured scheduler or the shared Reactor bounded-elastic scheduler.
     *
     * @param options writer options
     * @return scheduler used by a blocking writer
     */
    public static Scheduler resolve(ExcelOption... options) {
        Scheduler scheduler = Schedulers.boundedElastic();
        for (ExcelOption option : options) {
            if (option instanceof BlockingSchedulerOption) {
                scheduler = ((BlockingSchedulerOption) option).getScheduler();
            }
        }
        return scheduler;
    }

    /**
     * @return caller-owned blocking scheduler
     */
    public Scheduler getScheduler() {
        return scheduler;
    }
}
