/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - test tactic observing how proving is parallelised
 *******************************************************************************/
package org.eventb.core.tests.pom;

import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eventb.core.pm.IProofAttempt;
import org.eventb.core.seqprover.IProofMonitor;
import org.eventb.core.seqprover.IProofTreeNode;
import org.eventb.core.seqprover.ITactic;

/**
 * Records how the automatic prover schedules obligations, so tests can assert
 * that different proof components really do overlap and that obligations of one
 * component do not.
 * <p>
 * All state is static, because the tactic is instantiated by the extension
 * registry; {@link #reset(int)} must be called before each test. Every field is
 * touched from several threads at once, so none of it may be a plain
 * collection.
 * </p>
 */
public class ConcurrencyTactic implements ITactic {

	/** How long a rendezvous may take before the test is declared failed. */
	private static final long TIMEOUT_MS = 30_000;

	private static volatile CyclicBarrier barrier;

	private static volatile boolean fail;

	private static final Map<String, AtomicInteger> insideByComponent = new ConcurrentHashMap<>();

	private static final AtomicInteger overlapsWithinComponent = new AtomicInteger();

	private static final AtomicInteger applications = new AtomicInteger();

	private static volatile String rendezvousError;

	private static volatile long dwellMillis;

	/**
	 * @param parties
	 *            how many obligations must meet before any may proceed, or 0
	 *            for no rendezvous
	 */
	public static void reset(int parties) {
		barrier = parties > 0 ? new CyclicBarrier(parties) : null;
		fail = false;
		insideByComponent.clear();
		overlapsWithinComponent.set(0);
		applications.set(0);
		rendezvousError = null;
		dwellMillis = 0;
	}

	/**
	 * Makes each application linger, watching for another obligation of the
	 * same component to arrive.
	 * <p>
	 * Without this the tactic returns in nanoseconds, so obligations that are
	 * genuinely being proved on separate threads still never coincide, and an
	 * overlap that the implementation does allow goes unobserved.
	 * </p>
	 *
	 * @param millis
	 *            how long to watch for a same-component sibling
	 */
	public static void watchForOverlap(long millis) {
		dwellMillis = millis;
	}

	/** Makes the tactic throw, to check that failures reach the caller. */
	public static void failOnApply() {
		fail = true;
	}

	/** Releases anything still waiting, so a failed test cannot hang the run. */
	public static void release() {
		final CyclicBarrier current = barrier;
		if (current != null) {
			current.reset();
		}
	}

	public static int applicationCount() {
		return applications.get();
	}

	/** Number of times two obligations of one component were proved at once. */
	public static int overlapsWithinComponent() {
		return overlapsWithinComponent.get();
	}

	public static String rendezvousError() {
		return rendezvousError;
	}

	@Override
	public Object apply(IProofTreeNode node, IProofMonitor pm) {
		applications.incrementAndGet();
		final String component = componentOf(node);
		final AtomicInteger inside = insideByComponent.computeIfAbsent(
				component, k -> new AtomicInteger());
		inside.incrementAndGet();
		try {
			if (fail) {
				throw new IllegalStateException("tactic failed on purpose");
			}
			rendezvous();
			watchForSibling(inside);
			return "not proved";
		} finally {
			inside.decrementAndGet();
		}
	}

	/*
	 * Lingers, recording whether another obligation of the same component is
	 * being proved at the same time. Returns as soon as one is seen, so a
	 * correct implementation pays the full wait and a broken one does not.
	 */
	private static void watchForSibling(AtomicInteger inside) {
		final long deadline = System.currentTimeMillis() + dwellMillis;
		while (System.currentTimeMillis() < deadline) {
			if (inside.get() > 1) {
				overlapsWithinComponent.incrementAndGet();
				return;
			}
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		if (inside.get() > 1) {
			overlapsWithinComponent.incrementAndGet();
		}
	}

	/*
	 * Waits for the other obligations, if a rendezvous was asked for. A timeout
	 * is recorded rather than thrown, so the test fails with a message instead
	 * of the whole suite hanging -- neither of these bundles has a global test
	 * timeout.
	 */
	private static void rendezvous() {
		final CyclicBarrier current = barrier;
		if (current == null) {
			return;
		}
		try {
			current.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			rendezvousError = "obligations did not run at the same time";
		} catch (BrokenBarrierException e) {
			rendezvousError = "the rendezvous was broken";
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			rendezvousError = "interrupted while waiting";
		}
	}

	/*
	 * The proof attempt is the proof tree's origin, so the component is
	 * reachable from the node the tactic is handed -- no need to encode it in
	 * the obligation itself.
	 */
	private static String componentOf(IProofTreeNode node) {
		final Object origin = node.getProofTree().getOrigin();
		if (origin instanceof IProofAttempt) {
			return ((IProofAttempt) origin).getComponent().getPORoot()
					.getElementName();
		}
		return "unknown";
	}

}
