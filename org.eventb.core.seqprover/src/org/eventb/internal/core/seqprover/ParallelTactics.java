/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - initial implementation of the 'first successful' tactic
 *******************************************************************************/
package org.eventb.internal.core.seqprover;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.eventb.core.seqprover.IProofMonitor;
import org.eventb.core.seqprover.IProofSkeleton;
import org.eventb.core.seqprover.IProofTree;
import org.eventb.core.seqprover.IProofTreeNode;
import org.eventb.core.seqprover.ITactic;
import org.eventb.core.seqprover.tactics.BasicTactics;

/**
 * Runs several tactics against the same node at once and keeps the first proof
 * that succeeds.
 * <p>
 * Each tactic works on its own detached copy of the sub-tree, so no tactic can
 * observe or disturb another's work, and none of them touch the live proof tree
 * -- which is neither thread-safe nor prepared to have deltas fired at it from
 * several threads. Only the winning proof is brought back, by grafting its
 * skeleton onto the real node. The graft reuses the recorded rules and never
 * calls a reasoner, so the prover that won is not run a second time.
 * </p>
 */
public class ParallelTactics {

	private ParallelTactics() {
		// Utility class.
	}

	/**
	 * How long to wait for a result before re-checking whether the caller has
	 * cancelled. Tactics can block for a long time in an external prover, so
	 * waiting indefinitely would make cancellation unobservable.
	 */
	private static final long POLL_MILLIS = 100;

	private static final String ALL_FAILED = "All tactics failed";

	private static final AtomicInteger threadCount = new AtomicInteger();

	private static volatile ExecutorService pool;

	/**
	 * True on the threads of the shared pool. A tactic already running for this
	 * combinator runs any nested combinator's tactics itself rather than
	 * submitting more work: the tactics are typically external provers, and
	 * profiles nest this combinator inside itself.
	 */
	private static final ThreadLocal<Boolean> onPoolThread = ThreadLocal
			.withInitial(() -> Boolean.FALSE);

	private static ExecutorService getPool() {
		ExecutorService result = pool;
		if (result == null) {
			synchronized (ParallelTactics.class) {
				result = pool;
				if (result == null) {
					pool = result = newPool();
				}
			}
		}
		return result;
	}

	private static ExecutorService newPool() {
		// Sized well above the processor count on purpose: these tasks spend
		// their time blocked on an external prover rather than on a core, and
		// several obligations may be racing tactics at once, so a pool of one
		// thread per processor would queue them up and quietly turn the race
		// into a sequence. Idle threads retire so a single use does not leave
		// threads parked for the life of the bundle.
		final int size = Math.max(4,
				4 * Runtime.getRuntime().availableProcessors());
		final ThreadPoolExecutor executor = new ThreadPoolExecutor(size, size,
				60L, SECONDS, new LinkedBlockingQueue<Runnable>(), r -> {
					final Thread thread = new Thread(() -> {
						onPoolThread.set(Boolean.TRUE);
						r.run();
					}, "Rodin tactic " + threadCount.incrementAndGet());
					// Daemon, so a stuck prover cannot keep the JVM alive.
					thread.setDaemon(true);
					return thread;
				});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	/**
	 * Shuts the shared pool down. Called when the sequent prover stops.
	 */
	public static synchronized void shutdown() {
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}

	/**
	 * Applies the given tactics simultaneously and keeps the first proof found.
	 *
	 * @param pt
	 *            the node to prove
	 * @param pm
	 *            the caller's monitor, may be <code>null</code>
	 * @param tactics
	 *            the tactics to race
	 * @return <code>null</code> if the node was proved, a reason otherwise
	 */
	public static Object firstSuccessful(IProofTreeNode pt, IProofMonitor pm,
			ITactic[] tactics) {
		// A proof found on a copy can only be transferred back onto an open
		// node, so racing on a node that already has children would throw away
		// everything the tactics find. A single tactic needs no copy at all:
		// there is no other tactic to shield it from, and composeUntilSuccess
		// already reports the empty case.
		if (tactics.length <= 1 || !pt.isOpen()) {
			return BasicTactics.composeUntilSuccess(tactics).apply(pt, pm);
		}
		if (pm != null && pm.isCanceled()) {
			return Messages.tactic_cancelled;
		}
		if (onPoolThread.get()) {
			return sequential(pt, pm, tactics);
		}

		final CancelToken token = new CancelToken(pm);
		final CompletionService<IProofSkeleton> service = new ExecutorCompletionService<IProofSkeleton>(
				getPool());
		final List<Future<IProofSkeleton>> futures = new ArrayList<Future<IProofSkeleton>>(
				tactics.length);
		try {
			for (final ITactic tactic : tactics) {
				// Copy here rather than in the worker: the live tree must not
				// be read from another thread while we graft a winning proof
				// onto it, and a worker that starts late (there are more
				// tactics than pool threads) would do exactly that.
				final IProofTree copy = pt.copySubTree();
				futures.add(service.submit(() -> attempt(copy, tactic, token)));
			}
			for (int remaining = tactics.length; remaining > 0;) {
				if (pm != null && pm.isCanceled()) {
					return Messages.tactic_cancelled;
				}
				final Future<IProofSkeleton> done = service.poll(POLL_MILLIS,
						MILLISECONDS);
				if (done == null) {
					// Nothing finished yet; loop round to re-test cancellation.
					continue;
				}
				remaining--;
				final IProofSkeleton skeleton = result(done);
				if (skeleton != null && graft(pt, skeleton, pm)) {
					return null;
				}
			}
			return ALL_FAILED;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Messages.tactic_cancelled;
		} finally {
			// Whether we won, failed or were cancelled, no tactic should
			// outlive this call: they are pure work on discarded copies, so
			// letting them run on would only waste a prover process.
			token.setCanceled(true);
			for (final Future<IProofSkeleton> future : futures) {
				future.cancel(true);
			}
		}
	}

	/*
	 * Runs the tactics one after the other, but with the same discipline as the
	 * parallel path: each one works on its own copy, and only the proof of the
	 * first that succeeds is transferred. Running them directly on the node
	 * instead would let a tactic that fails leave rules behind, so the next
	 * tactic would start from a node that is no longer open.
	 */
	private static Object sequential(IProofTreeNode pt, IProofMonitor pm,
			ITactic[] tactics) {
		final CancelToken token = new CancelToken(pm);
		for (final ITactic tactic : tactics) {
			if (pm != null && pm.isCanceled()) {
				return Messages.tactic_cancelled;
			}
			final IProofSkeleton skeleton = attempt(pt.copySubTree(), tactic,
					token);
			if (skeleton != null && graft(pt, skeleton, pm)) {
				return null;
			}
		}
		return ALL_FAILED;
	}

	/*
	 * Transfers a proof found on a copy onto the real node, answering whether
	 * it took. Reuse replays no reasoner, so the prover that found the proof is
	 * not asked to find it again -- which matters because provers are bounded
	 * by a wall-clock timeout and need not answer the same way twice.
	 */
	private static boolean graft(IProofTreeNode pt, IProofSkeleton skeleton,
			IProofMonitor pm) {
		final Object reused = BasicTactics.reuseTac(skeleton).apply(pt, pm);
		if (reused == null) {
			return true;
		}
		// Reusing a proof built from this very node's sequent should not fail.
		// If it does it may have applied some rules before giving up: put the
		// node back as it was found, so the tactics still running have
		// somewhere to graft onto.
		if (!pt.isOpen()) {
			if (pt.isClosed()) {
				// Reported failure yet the node came out proved: keep it.
				return true;
			}
			pt.pruneChildren();
		}
		Util.log(null, "Could not apply a proof found in parallel: " + reused);
		return false;
	}

	/*
	 * Runs one tactic on a detached copy of the node, returning the proof it
	 * found or null if it did not find one.
	 */
	private static IProofSkeleton attempt(IProofTree copy, ITactic tactic,
			CancelToken token) {
		if (token.isCanceled()) {
			return null;
		}
		if (tactic.apply(copy.getRoot(), token) != null) {
			return null;
		}
		return copy.getRoot().copyProofSkeleton();
	}

	private static IProofSkeleton result(Future<IProofSkeleton> future)
			throws InterruptedException {
		try {
			return future.get();
		} catch (CancellationException e) {
			return null;
		} catch (ExecutionException e) {
			Util.log(e.getCause(), "while running a tactic in parallel");
			return null;
		}
	}

	/*
	 * Monitor handed to the racing tactics. It carries cancellation both ways --
	 * from the caller, and from this combinator once a winner is known -- but
	 * deliberately drops setTask: the caller's monitor ultimately forwards that
	 * to an Eclipse SubMonitor, which is not thread-safe.
	 */
	private static final class CancelToken implements IProofMonitor {

		private final IProofMonitor delegate;

		private volatile boolean canceled;

		CancelToken(IProofMonitor delegate) {
			this.delegate = delegate;
		}

		@Override
		public boolean isCanceled() {
			return canceled || (delegate != null && delegate.isCanceled());
		}

		@Override
		public void setCanceled(boolean value) {
			canceled = value;
		}

		@Override
		public void setTask(String name) {
			// Ignored: see above.
		}
	}

}
