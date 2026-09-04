/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - parallel proving across proof components
 *******************************************************************************/
package org.eventb.internal.core.pom;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eventb.core.EventBPlugin;
import org.eventb.core.IPSRoot;
import org.eventb.core.IPSStatus;
import org.eventb.core.pm.IProofComponent;
import org.eventb.core.pm.IProofManager;
import org.eventb.internal.core.seqprover.ParallelTactics;

/**
 * Runs proof work for several proof components at once.
 * <p>
 * The unit of parallelism is the proof component, not the proof obligation.
 * Every database operation on a component already acquires that component's
 * scheduling rule -- a rule over its three files -- so two components never
 * conflict, while the obligations of one component stay ordered and share a
 * single save. Splitting per obligation instead would have workers of the same
 * component writing the same two files, and would give each of them its own
 * save.
 * </p>
 * <p>
 * The caller's monitor is only ever touched by the calling thread: workers get
 * their own child, created before they are handed the work. Failures are
 * collected and reported together rather than being written to the console by
 * whichever thread happened to hit one.
 * </p>
 */
public class ProvingPool {

	private ProvingPool() {
		// Utility class.
	}

	/** How long to wait for a component before re-checking cancellation. */
	private static final long POLL_MILLIS = 100;

	private static final AtomicInteger threadCount = new AtomicInteger();

	private static volatile ExecutorService pool;

	private static ExecutorService getPool() {
		ExecutorService result = pool;
		if (result == null) {
			synchronized (ProvingPool.class) {
				result = pool;
				if (result == null) {
					pool = result = newPool();
				}
			}
		}
		return result;
	}

	private static ExecutorService newPool() {
		// One thread per processor: unlike a racing tactic, which sits waiting
		// for an external prover, this work mixes reasoning with database
		// access and contends on the Rodin database's single lock, so more
		// threads would buy queueing rather than throughput. Idle threads
		// retire, so proving once does not leave threads parked for the life
		// of the bundle.
		final int size = Math.max(2, Runtime.getRuntime()
				.availableProcessors());
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size,
				60L, SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
					final Thread thread = new Thread(r, "Rodin prover "
							+ threadCount.incrementAndGet());
					// Daemon, so a stuck prover cannot keep the JVM alive.
					thread.setDaemon(true);
					return thread;
				});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	/**
	 * Shuts the shared pool down. Called when the Event-B core stops.
	 */
	public static synchronized void shutdown() {
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}

	/**
	 * A piece of work for one proof component.
	 */
	public interface ComponentTask {

		/**
		 * @param monitor
		 *            this task's own monitor, not shared with any other task
		 */
		void run(IProgressMonitor monitor) throws CoreException;
	}

	/**
	 * Groups obligations by the proof component that owns them, keeping the
	 * order they were given in.
	 * <p>
	 * Keyed on the PS root rather than on the proof component, because the
	 * proof manager holds components through soft references and may hand out
	 * a fresh instance for the same root.
	 * </p>
	 *
	 * @param statuses
	 *            the obligations to group
	 * @return one entry per component, in first-seen order
	 */
	public static Map<IProofComponent, List<IPSStatus>> groupByComponent(
			Collection<IPSStatus> statuses) {
		final Map<IPSRoot, List<IPSStatus>> byRoot = new LinkedHashMap<IPSRoot, List<IPSStatus>>();
		for (final IPSStatus status : statuses) {
			final IPSRoot psRoot = (IPSRoot) status.getRoot();
			byRoot.computeIfAbsent(psRoot, k -> new ArrayList<IPSStatus>())
					.add(status);
		}
		final IProofManager pm = EventBPlugin.getProofManager();
		final Map<IProofComponent, List<IPSStatus>> byComponent = new LinkedHashMap<IProofComponent, List<IPSStatus>>(
				byRoot.size());
		for (final Map.Entry<IPSRoot, List<IPSStatus>> entry : byRoot
				.entrySet()) {
			byComponent.put(pm.getProofComponent(entry.getKey()),
					entry.getValue());
		}
		return byComponent;
	}

	/**
	 * Runs one task per proof component and waits for them all.
	 * <p>
	 * Returns only once every task has finished or been abandoned, so a caller
	 * that returns to its own caller can be sure nothing is still writing.
	 * </p>
	 *
	 * @param tasks
	 *            the work, one entry per component
	 * @param monitor
	 *            the caller's monitor; only this thread touches it
	 * @throws OperationCanceledException
	 *             if the monitor was cancelled
	 * @throws CoreException
	 *             if any task failed, describing every failure
	 */
	public static void runAll(List<ComponentTask> tasks,
			IProgressMonitor monitor) throws CoreException {
		if (tasks.isEmpty()) {
			return;
		}
		final SubMonitor progress = SubMonitor.convert(monitor, tasks.size());
		if (tasks.size() == 1) {
			// Nothing to overlap with: keep it on this thread, where it may
			// use the caller's monitor as usual, and leave it unmarked so that
			// a racing tactic combinator is free to use the idle cores. That
			// is also what this path did before there was a component pool.
			tasks.get(0).run(progress.split(1));
			return;
		}
		// Workers never touch the caller's monitor -- not even a child of it,
		// because a SubMonitor child forwards subTask and worked to the root.
		// They share a NullProgressMonitor, whose cancellation flag is
		// volatile and whose every other method is a no-op; this thread
		// reports the progress as each component finishes.
		final IProgressMonitor canceler = new NullProgressMonitor();
		final CompletionService<IStatus> service = new ExecutorCompletionService<IStatus>(
				getPool());
		final List<Future<IStatus>> futures = new ArrayList<Future<IStatus>>(
				tasks.size());
		final List<IStatus> failures = new ArrayList<IStatus>();
		// Distinct from the cancellation flag: that one is also raised by the
		// cleanup below, so it cannot be used to decide what to report.
		boolean canceledByCaller = false;
		try {
			for (final ComponentTask task : tasks) {
				futures.add(service.submit(asCallable(task, canceler)));
			}
			for (int remaining = tasks.size(); remaining > 0;) {
				if (progress.isCanceled()) {
					canceledByCaller = true;
					canceler.setCanceled(true);
				}
				final Future<IStatus> done = service.poll(POLL_MILLIS,
						MILLISECONDS);
				if (done == null) {
					// Still working; loop round to re-check cancellation.
					continue;
				}
				remaining--;
				progress.worked(1);
				record(failures, done);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			canceler.setCanceled(true);
			throw new OperationCanceledException();
		} finally {
			// Nothing may still be running once this returns.
			canceler.setCanceled(true);
			for (final Future<IStatus> future : futures) {
				future.cancel(true);
			}
		}
		if (canceledByCaller) {
			throw new OperationCanceledException();
		}
		if (!failures.isEmpty()) {
			throw new CoreException(new MultiStatus(EventBPlugin.PLUGIN_ID,
					IStatus.OK,
					failures.toArray(new IStatus[failures.size()]),
					"Proving failed for some components", null));
		}
	}

	/*
	 * Folds one finished task into the failures, so that one broken component
	 * does not hide the others.
	 */
	private static void record(List<IStatus> failures, Future<IStatus> future) {
		final IStatus status;
		try {
			status = future.get();
		} catch (CancellationException e) {
			return;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		} catch (ExecutionException e) {
			failures.add(Status.error("Proving failed", e.getCause()));
			return;
		}
		if (status != null) {
			failures.add(status);
		}
	}

	private static Callable<IStatus> asCallable(ComponentTask task,
			IProgressMonitor monitor) {
		return () -> {
			final IStatus[] result = new IStatus[1];
			// Marked as already proving in parallel, so that a tactic
			// combinator reached from here runs sequentially rather than
			// asking for threads of its own.
			ParallelTactics.runAsProvingWorker(() -> {
				try {
					task.run(monitor);
				} catch (OperationCanceledException e) {
					// The caller sees this through the monitor.
				} catch (CoreException e) {
					result[0] = e.getStatus();
				} catch (RuntimeException e) {
					result[0] = Status.error("Proving failed", e);
				}
			});
			return result[0];
		};
	}

}
