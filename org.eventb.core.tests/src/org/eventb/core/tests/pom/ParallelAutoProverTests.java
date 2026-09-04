/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - tests for proving several proof components at once
 *******************************************************************************/
package org.eventb.core.tests.pom;

import static org.eventb.core.tests.pom.POUtil.addPredicateSet;
import static org.eventb.core.tests.pom.POUtil.addSequent;
import static org.eventb.core.tests.pom.POUtil.mTypeEnvironment;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eventb.core.EventBPlugin;
import org.eventb.core.IPORoot;
import org.eventb.core.IPSRoot;
import org.eventb.core.IPSStatus;
import org.eventb.core.ast.ITypeEnvironment;
import org.eventb.core.tests.BuilderTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.rodinp.core.RodinDBException;

/**
 * The automatic prover proves obligations of different proof components at the
 * same time, and obligations of one component one after another.
 * <p>
 * A component is three files and one scheduling rule, so obligations of one
 * component must not overlap; components are independent, so they should. These
 * tests pin both halves, plus what happens to a failure and to the caller's
 * progress monitor -- all of which used to go unnoticed, because every worker
 * exception was written to the console and the job still reported success.
 * </p>
 */
public class ParallelAutoProverTests extends BuilderTest {

	private static final String TACTIC_ID = "org.eventb.core.tests.concurrencyTac";

	/** Obligations per component. */
	private static final int PER_COMPONENT = 3;

	@Before
	public void startFromAKnownState() throws Exception {
		ConcurrencyTactic.reset(0);
	}

	/*
	 * Runs before BuilderTest's own teardown, which deletes the workspace: a
	 * tactic still parked on a rendezvous would hold files open and hang the
	 * whole suite, since neither bundle installs a test timeout.
	 */
	@After
	public void releaseAnythingStillWaiting() {
		ConcurrencyTactic.release();
	}

	/**
	 * Two components must be able to prove at the same time. The tactic waits
	 * for its counterpart, so proving them one after the other times out at the
	 * rendezvous and fails here with a message rather than hanging.
	 */
	@Test
	public void componentsAreProvedAtTheSameTime() throws Exception {
		final Set<IPSStatus> statuses = createComponents("c1", "c2");
		// One obligation of each component must meet the other.
		ConcurrencyTactic.reset(2);

		runAutoProver(statuses);

		assertNull(ConcurrencyTactic.rendezvousError(),
				ConcurrencyTactic.rendezvousError());
	}

	/**
	 * Obligations of one component share a scheduling rule and a save, so they
	 * must be proved one at a time.
	 */
	@Test
	public void obligationsOfOneComponentDoNotOverlap() throws Exception {
		final Set<IPSStatus> statuses = createComponents("c1", "c2", "c3");
		// Each application watches briefly for a sibling of its own component,
		// so an overlap the implementation permits is actually seen.
		ConcurrencyTactic.watchForOverlap(200);

		runAutoProver(statuses);

		assertEquals("every obligation should have been attempted",
				3 * PER_COMPONENT, ConcurrencyTactic.applicationCount());
		assertEquals("two obligations of one component were proved at once", 0,
				ConcurrencyTactic.overlapsWithinComponent());
	}

	/**
	 * A tactic that throws must reach the caller. It used to be handed to the
	 * worker thread's uncaught-exception handler, so proving reported success
	 * and the obligation was silently skipped.
	 */
	@Test
	public void aFailingObligationIsReported() throws Exception {
		final Set<IPSStatus> statuses = createComponents("c1", "c2");
		ConcurrencyTactic.failOnApply();

		try {
			runAutoProver(statuses);
			fail("the failure should have been reported to the caller");
		} catch (RodinDBException e) {
			assertNotNull(e.getStatus());
		}
	}

	/**
	 * Cancelling must stop the run and tell the caller. The fan-out this
	 * replaced caught the interruption around its own wait, so the call could
	 * return normally -- and its progress dialog close -- while workers were
	 * still writing the workspace.
	 */
	@Test
	public void cancellingIsReportedToTheCaller() throws Exception {
		final Set<IPSStatus> statuses = createComponents("c1", "c2");
		// Each application lingers, so that the run is still going when the
		// monitor starts reporting cancellation.
		ConcurrencyTactic.watchForOverlap(200);

		try {
			EventBPlugin.runAutoProver(statuses, new CancelOnFirstProof());
			fail("cancelling should have been reported to the caller");
		} catch (OperationCanceledException e) {
			// Expected: the caller asked to stop, and is told that it did.
		}
	}

	/*
	 * Reports cancellation once proving has actually started, so the run is
	 * cancelled with work still outstanding rather than before it begins.
	 */
	private static final class CancelOnFirstProof extends NullProgressMonitor {

		@Override
		public boolean isCanceled() {
			return ConcurrencyTactic.applicationCount() > 0;
		}
	}

	/**
	 * Only the calling thread may touch the caller's monitor: a progress
	 * monitor is not safe to use from several threads, and doing so is what
	 * made the progress dialog finish early.
	 */
	@Test
	public void onlyTheCallingThreadTouchesTheMonitor() throws Exception {
		final Set<IPSStatus> statuses = createComponents("c1", "c2", "c3");
		final ThreadRecordingMonitor monitor = new ThreadRecordingMonitor();

		EventBPlugin.runAutoProver(statuses, monitor);

		assertNull("the caller's monitor was used from another thread",
				monitor.offendingThread);
	}

	private void runAutoProver(Set<IPSStatus> statuses)
			throws RodinDBException {
		EventBPlugin.runAutoProver(statuses, new NullProgressMonitor());
	}

	/*
	 * Creates one proof component per name, each with PER_COMPONENT
	 * obligations, and returns every resulting status. The goal of each
	 * obligation names its component, which is how the tactic tells them apart.
	 */
	private Set<IPSStatus> createComponents(String... names)
			throws Exception {
		for (final String name : names) {
			final IPORoot poRoot = createPOFile(name);
			final ITypeEnvironment te = mTypeEnvironment();
			final var predicates = addPredicateSet(poRoot, "h" + name, null, te);
			for (int i = 0; i < PER_COMPONENT; i++) {
				addSequent(poRoot, "PO" + i, "\u22a4", predicates, te);
			}
			saveRodinFileOf(poRoot);
		}
		// Build with the prover off: the fixture is only here to produce the
		// obligations. Proving during the build would count as a run of its
		// own and double every observation the tactic makes.
		disableAutoProver();
		runBuilder();
		enableAutoProver(TACTIC_ID);
		ConcurrencyTactic.reset(0);

		final Set<IPSStatus> statuses = new LinkedHashSet<IPSStatus>();
		for (final String name : names) {
			final IPSRoot psRoot = eventBProject.getPSRoot(name);
			for (final IPSStatus status : psRoot.getStatuses()) {
				statuses.add(status);
			}
		}
		assertEquals(names.length * PER_COMPONENT, statuses.size());
		return statuses;
	}

	/**
	 * Notes the first thread other than its creator to call it.
	 */
	private static final class ThreadRecordingMonitor extends
			NullProgressMonitor {

		private final Thread owner = Thread.currentThread();

		volatile Thread offendingThread;

		private void check() {
			if (Thread.currentThread() != owner && offendingThread == null) {
				offendingThread = Thread.currentThread();
			}
		}

		@Override
		public void beginTask(String name, int totalWork) {
			check();
			super.beginTask(name, totalWork);
		}

		@Override
		public void worked(int work) {
			check();
			super.worked(work);
		}

		@Override
		public void subTask(String name) {
			check();
			super.subTask(name);
		}

		@Override
		public void done() {
			check();
			super.done();
		}

		@Override
		public void setTaskName(String name) {
			check();
			super.setTaskName(name);
		}
	}
}
