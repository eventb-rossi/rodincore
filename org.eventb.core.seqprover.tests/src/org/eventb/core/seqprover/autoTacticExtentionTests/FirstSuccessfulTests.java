/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - tests for the 'first successful' tactic combinator
 *******************************************************************************/
package org.eventb.core.seqprover.autoTacticExtentionTests;

import static org.eventb.core.seqprover.tactics.BasicTactics.compose;
import static org.eventb.core.seqprover.tactics.BasicTactics.failTac;
import static org.eventb.core.seqprover.tactics.BasicTactics.firstSuccessful;
import static org.eventb.core.seqprover.tactics.BasicTactics.onAllPending;
import static org.eventb.core.seqprover.tests.TestLib.genProofTreeNode;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eventb.core.seqprover.IProofMonitor;
import org.eventb.core.seqprover.IProofTreeNode;
import org.eventb.core.seqprover.ITactic;
import org.eventb.core.seqprover.eventbExtensions.AutoTactics;
import org.eventb.core.seqprover.eventbExtensions.Tactics;
import org.junit.Test;

/**
 * Tests for {@link org.eventb.core.seqprover.tactics.BasicTactics#firstSuccessful}.
 *
 * The combinator races tactics against each other, so the properties worth
 * pinning down are that a losing tactic cannot be observed at all, that the
 * winner's proof really lands on the node, and that nothing keeps running once
 * the combinator has returned.
 */
public class FirstSuccessfulTests {

	// A conjunction, so that a tactic can visibly modify a node (by splitting
	// it) as well as discharge it.
	private static final String GOAL = "⊤ ∧ ⊤";

	private static final long TIMEOUT_MS = 30_000;

	private static IProofTreeNode makeNode() {
		return genProofTreeNode("⊤ |- " + GOAL);
	}

	/** Splits the conjunction, then discharges both halves. */
	private static ITactic discharging() {
		return compose(Tactics.conjI(), onAllPending(new AutoTactics.TrueGoalTac()));
	}

	/** Modifies its node and then reports failure. */
	private static ITactic splitThenFail() {
		return (node, pm) -> {
			Tactics.conjI().apply(node, pm);
			return "loser failed";
		};
	}

	/**
	 * Blocks until cancelled, announcing when it starts and when it stops. The
	 * started latch matters: a racer that is cancelled before it ever runs
	 * never executes its finally block, so a test that waits on stopped alone
	 * would hang rather than fail.
	 */
	private static ITactic blocking(CountDownLatch started, CountDownLatch stopped) {
		return (node, pm) -> {
			started.countDown();
			try {
				while (!pm.isCanceled()) {
					Thread.sleep(5);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				stopped.countDown();
			}
			return "blocked tactic failed";
		};
	}

	/** Waits for the blockers to be running, then discharges. */
	private static ITactic awaitThen(CountDownLatch started, ITactic then) {
		return (node, pm) -> {
			try {
				if (!started.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
					return "the other tactics never started";
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "interrupted";
			}
			return then.apply(node, pm);
		};
	}

	private static final class CancellableMonitor implements IProofMonitor {
		private volatile boolean canceled;

		@Override
		public boolean isCanceled() {
			return canceled;
		}

		@Override
		public void setCanceled(boolean value) {
			canceled = value;
		}

		@Override
		public void setTask(String name) {
			// Not used.
		}
	}

	/**
	 * A tactic that loses must leave no trace: not on the node, and not in the
	 * change notifications the tree sends out. The deltas are the part that
	 * matters -- a tactic run on an attached copy modifies the copy, which is
	 * never visible in the real node's children, but does fire notifications
	 * describing nodes that are not in the tree.
	 */
	@Test
	public void losingTacticsLeaveTheTreeAlone() {
		final IProofTreeNode node = makeNode();
		final AtomicInteger deltas = new AtomicInteger();
		node.getProofTree().addChangeListener(delta -> deltas.incrementAndGet());

		final Object result = firstSuccessful(splitThenFail(), splitThenFail(),
				splitThenFail()).apply(node, null);

		assertNotNull("expected failure when every tactic fails", result);
		assertTrue("the node must still be open", node.isOpen());
		assertEquals("a losing tactic modified the real node", 0,
				node.getChildNodes().length);
		assertEquals("a losing tactic changed the live proof tree", 0,
				deltas.get());
	}

	/** The winning proof must actually be transferred onto the node. */
	@Test
	public void winningProofIsApplied() {
		final IProofTreeNode node = makeNode();

		final Object result = firstSuccessful(failTac("failed"), discharging(),
				failTac("failed")).apply(node, null);

		assertNull("expected success", result);
		assertTrue("the winning proof was not applied", node.isClosed());
	}

	/**
	 * A tactic that only succeeds the first time still has to leave the node
	 * discharged. Re-running the winner instead of transferring the proof it
	 * already found gets this wrong -- and external provers really are
	 * non-deterministic, because they are bounded by wall-clock timeouts.
	 */
	@Test
	public void nonDeterministicWinnerStillDischarges() {
		final IProofTreeNode node = makeNode();
		final AtomicInteger calls = new AtomicInteger();
		final ITactic onceOnly = (n, pm) -> {
			if (calls.getAndIncrement() > 0) {
				return "not this time";
			}
			return discharging().apply(n, pm);
		};

		final Object result = firstSuccessful(failTac("failed"), onceOnly).apply(node,
				null);

		assertNull("expected success", result);
		assertTrue("the node must be discharged", node.isClosed());
	}

	/**
	 * Once a winner is known the other tactics are pointless, so they must be
	 * stopped rather than left running against a discarded copy.
	 */
	@Test
	public void losersAreStopped() throws Exception {
		final IProofTreeNode node = makeNode();
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch stopped = new CountDownLatch(1);

		// A real monitor, never cancelled: the losing tactic must be stopped by
		// this combinator, not by the caller giving up.
		final Object result = firstSuccessful(awaitThen(started, discharging()),
				blocking(started, stopped)).apply(node,
						new CancellableMonitor());

		assertNull("expected success", result);
		assertTrue("a losing tactic was left running",
				stopped.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
	}

	/**
	 * Cancelling has to take effect while the tactics are still working, not
	 * only once one of them happens to finish.
	 */
	@Test
	public void cancellationIsObservedWhileRunning() throws Exception {
		final IProofTreeNode node = makeNode();
		final CancellableMonitor monitor = new CancellableMonitor();
		final CountDownLatch started = new CountDownLatch(2);
		final CountDownLatch stopped = new CountDownLatch(2);
		// Cancelling from a racer keeps this deterministic without a sleep, but
		// it must wait until the others are running: cancelling sooner would
		// stop them before they start, and they would never report back.
		final ITactic canceller = awaitThen(started, (n, pm) -> {
			monitor.setCanceled(true);
			return "cancelled";
		});

		final Object result = firstSuccessful(canceller,
				blocking(started, stopped), blocking(started, stopped))
						.apply(node, monitor);

		assertNotNull("cancellation must be reported", result);
		assertTrue("the node must be left alone", node.isOpen());
		assertTrue("the tactics were not stopped",
				stopped.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
	}

	/**
	 * Nesting must not multiply threads: a combinator running inside another
	 * one falls back to running its tactics in turn.
	 */
	@Test
	public void nestingDoesNotDeadlock() {
		final IProofTreeNode node = makeNode();
		final ITactic inner = firstSuccessful(failTac("failed"), discharging());
		final ITactic outer = firstSuccessful(failTac("failed"), inner);

		final Object result = outer.apply(node, null);

		assertNull("expected success", result);
		assertTrue(node.isClosed());
	}
}
