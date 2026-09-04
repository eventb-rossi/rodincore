/*******************************************************************************
 * Copyright (c) 2005, 2021 ETH Zurich and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     ETH Zurich - initial API and implementation
 *     Systerel - refactored for using the Proof Manager API
 *     Systerel - refactored code to improve maintainability
 *     Systerel - added proof simplification on commit
 *     Systerel - fixed bar progression
 *     Systerel - added simplify proof preference
 *     ISP RAS - parallelize code
 *******************************************************************************/
package org.eventb.internal.core.pom;

import static org.eventb.core.seqprover.IConfidence.PENDING;
import static java.util.Arrays.asList;
import static org.eventb.internal.core.pom.AutoPOM.tryMakeConsistent;
import static org.eventb.internal.core.preferences.PreferenceUtils.getSimplifyProofPref;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import org.eventb.core.EventBPlugin;
import org.eventb.core.IPSRoot;
import org.eventb.core.IPSStatus;
import org.eventb.core.pm.IProofAttempt;
import org.eventb.core.pm.IProofComponent;
import org.eventb.core.pm.IProofManager;
import org.eventb.core.preferences.autotactics.IAutoPostTacticManager;
import org.eventb.core.seqprover.IProofTree;
import org.eventb.core.seqprover.ITactic;
import org.eventb.internal.core.ProofMonitor;
import org.eventb.internal.core.pom.ProvingPool.ComponentTask;
import org.rodinp.core.RodinDBException;

/**
 * @author Laurent Voisin
 *
 */
public final class AutoProver {

	public static final String AUTO_PROVER = "auto-prover";

	private static final IAutoPostTacticManager AUTOTACTIC_MANAGER = EventBPlugin
			.getAutoPostTacticManager();

	public static boolean isEnabled() {
		return AUTOTACTIC_MANAGER.getAutoTacticPreference().isEnabled();
	}

	private AutoProver() {
		// Nothing to do.
	}

	public static void run(IProofComponent pc, IPSStatus[] pos,
			IProgressMonitor monitor) throws RodinDBException {
		run(pc, pos, AUTOTACTIC_MANAGER.getSelectedAutoTactics(pc.getPORoot()),
				monitor);
	}

	public static void run(IProofComponent pc, IPSStatus[] pos,
			ITactic tactic, IProgressMonitor monitor) throws RodinDBException {
		final SubMonitor sMonitor = SubMonitor.convert(monitor, "auto-proving", pos.length + 1);
		boolean dirty = false;
		try {
			for (IPSStatus status : pos) {
				dirty |= processPo(pc, status, tactic, sMonitor.split(1));
			}
			if (dirty) {
				pc.save(sMonitor.split(1), false);
			} else {
				sMonitor.worked(1);
			}
		} catch(OperationCanceledException e) {
			tryMakeConsistent(pc);
			throw e;
		} finally {
			monitor.done();
		}
	}

	public static void run(IPSStatus[] pos, IProgressMonitor monitor)
			throws RodinDBException {
		try {
			ProvingPool.runAll(componentTasks(pos), monitor);
		} catch (RodinDBException e) {
			throw e;
		} catch (CoreException e) {
			// Anything the proof manager did not already report as a database
			// failure is a genuine failure of one component's proving.
			throw new RodinDBException(e);
		} finally {
			monitor.done();
		}
	}

	/*
	 * One task per proof component, each proving that component's obligations
	 * in turn.
	 *
	 * Components are the unit of parallelism because a component's three files
	 * are what its scheduling rule covers: two components never conflict, while
	 * obligations of one component share a rule, an order and a single save.
	 */
	private static List<ComponentTask> componentTasks(IPSStatus[] pos) {
		final Map<IProofComponent, List<IPSStatus>> byComponent = ProvingPool
				.groupByComponent(asList(pos));
		final List<ComponentTask> tasks = new ArrayList<ComponentTask>(
				byComponent.size());
		for (final Map.Entry<IProofComponent, List<IPSStatus>> entry : byComponent
				.entrySet()) {
			final IProofComponent pc = entry.getKey();
			final List<IPSStatus> group = entry.getValue();
			final IPSStatus[] statuses = group.toArray(new IPSStatus[group
					.size()]);
			// The tactic is resolved once per component, here on the calling
			// thread. Resolving it per obligation re-reads and re-parses the
			// tactic preference every time, under a lock shared by every
			// thread.
			final ITactic tactic = AUTOTACTIC_MANAGER
					.getSelectedAutoTactics(pc.getPORoot());
			tasks.add(m -> run(pc, statuses, tactic, m));
		}
		return tasks;
	}

	private static boolean processPo(IProofComponent pc, IPSStatus status,
			ITactic tactic, IProgressMonitor pm) throws RodinDBException {

		final String poName = status.getElementName();
		try {
			pm.beginTask(poName + ":", 3);
			final IProofAttempt pa = load(pc, poName, pm);
			try {
				prove(pa, tactic, pm);
				return commit(pa, pm);
			} finally {
				pa.dispose();
			}
		} finally {
			pm.done();
		}
	}

	// Consumes one tick of the given progress monitor
	private static IProofAttempt load(IProofComponent pc, String poName,
			IProgressMonitor pm) throws RodinDBException {
		final SubMonitor sMonitor = SubMonitor.convert(pm, 1);
		sMonitor.subTask("loading");
		return pc.createProofAttempt(poName, AUTO_PROVER, sMonitor.split(1));
	}

	// Consumes one tick of the given progress monitor
	private static void prove(IProofAttempt pa, ITactic tactic,
			IProgressMonitor pm) {
		final SubMonitor sMonitor = SubMonitor.convert(pm, 1);
		sMonitor.subTask("proving");
		tactic.apply(pa.getProofTree().getRoot(), new ProofMonitor(sMonitor.split(1)));
	}

	// Consumes one tick of the given progress monitor
	private static boolean commit(IProofAttempt pa, IProgressMonitor pm)
			throws RodinDBException {
		final SubMonitor sMonitor = SubMonitor.convert(pm, 1);
		sMonitor.subTask("committing");
		if (shouldCommit(pa)) {
			pa.commit(false, getSimplifyProofPref(), sMonitor.split(1));
			return true;
		}
		sMonitor.worked(1);
		return false;
	}

	private static boolean shouldCommit(IProofAttempt pa)
			throws RodinDBException {
		final IProofTree pt = pa.getProofTree();
		if (pt.isClosed()) {
			// The auto-prover discharged the PO.
			return true;
		}
		if (pt.getRoot().hasChildren()) {
			// The auto prover made 'some' progress
			final IPSStatus prevStatus = pa.getStatus();
			final boolean wasAuto = !prevStatus.getHasManualProof();
			final boolean wasUseless = prevStatus.getConfidence() <= PENDING;
			if (wasAuto && wasUseless) {
				return true;
			}
		}
		return false;
	}
}
