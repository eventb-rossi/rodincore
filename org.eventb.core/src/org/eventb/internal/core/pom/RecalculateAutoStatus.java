/*******************************************************************************
 * Copyright (c) 2005, 2021 ETH Zurich and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     ETH Zurich - initial API and implementation
 *     Systerel - separation of file and root element
 *     Systerel - used proof components
 *     Systerel - added simplify proof preference
 *     ISP RAS - parallelize code
 *******************************************************************************/
package org.eventb.internal.core.pom;

import static org.eventb.internal.core.preferences.PreferenceUtils.getSimplifyProofPref;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubMonitor;
import static org.eventb.internal.core.pom.AutoPOM.tryMakeConsistent;

import org.eventb.core.EventBPlugin;
import org.eventb.core.IEventBRoot;
import org.eventb.core.IPSRoot;
import org.eventb.core.IPSStatus;
import org.eventb.core.pm.IProofAttempt;
import org.eventb.core.pm.IProofComponent;
import org.eventb.core.pm.IProofManager;
import org.eventb.core.seqprover.IProofTree;
import org.eventb.core.seqprover.ITactic;
import org.eventb.internal.core.Messages;
import org.eventb.internal.core.ProofMonitor;
import org.eventb.internal.core.pom.ProvingPool.ComponentTask;
import org.rodinp.core.RodinCore;
import org.rodinp.core.RodinDBException;

/**
 * This class implements a run method that reruns the current auto prover on ALL
 * given IPSStatus elements. The main aim of this method is to update the "has
 * manual proof" status to reflect the current auto provers selection. In case a
 * proof was previously automatically discharged and is no more, it will be
 * marked as manually created.
 * 
 * This is intended to be used as a post-development operation to estimate the
 * percentage of automated proofs over a changing auto prover. It is recommended
 * to restore to the default autoprovers before running this.
 * 
 * WARNING : This code is not yet mature. WARNING : The run method will discard
 * all proofs (manual or otherwise) that can now be discharged automatically.
 * 
 * @author Farhad Mehta
 * 
 */
public final class RecalculateAutoStatus {

	public static boolean DEBUG;

	private static final String REC_AUTO = "Recalculate auto status"; //$NON-NLS-1$
	
	private RecalculateAutoStatus() {
		// Nothing to do.
	}

	public static void run(Set<IPSStatus> pos, IProgressMonitor monitor)
			throws RodinDBException {
		try {
			ProvingPool.runAll(componentTasks(pos), monitor);
		} catch (RodinDBException e) {
			throw e;
		} catch (CoreException e) {
			throw new RodinDBException(e);
		} finally {
			monitor.done();
		}
	}

	/*
	 * One task per proof component.
	 */
	private static List<ComponentTask> componentTasks(Set<IPSStatus> pos) {
		final Map<IProofComponent, List<IPSStatus>> byComponent = ProvingPool
				.groupByComponent(pos);
		final List<ComponentTask> tasks = new ArrayList<ComponentTask>(
				byComponent.size());
		for (final Map.Entry<IProofComponent, List<IPSStatus>> entry : byComponent
				.entrySet()) {
			final IProofComponent pc = entry.getKey();
			final List<IPSStatus> statuses = entry.getValue();
			// Resolved once per component, on the calling thread: the lookup
			// re-reads and re-parses the tactic preference under a shared lock.
			final ITactic tactic = autoTactic(pc.getPORoot());
			tasks.add(m -> runComponent(pc, statuses, tactic, m));
		}
		return tasks;
	}

	/*
	 * Recalculates one component's obligations, in order, under that
	 * component's scheduling rule.
	 *
	 * The rule is taken here for the whole component rather than left to each
	 * operation, because one of the writes below -- marking an obligation as
	 * manually proved -- does not modify resources as far as the Rodin database
	 * is concerned and so takes no rule of its own. Holding the component's
	 * rule around the batch covers it, and gives the component a single save.
	 */
	private static void runComponent(IProofComponent pc,
			List<IPSStatus> statuses, ITactic tactic, IProgressMonitor monitor)
			throws RodinDBException {
		final SubMonitor sMonitor = SubMonitor.convert(monitor,
				statuses.size() + 1);
		RodinCore.run(m -> {
			boolean dirty = false;
			try {
				for (final IPSStatus status : statuses) {
					dirty |= processPo(pc, status, tactic, sMonitor.split(1));
				}
				if (dirty) {
					pc.save(sMonitor.split(1), false);
				} else {
					sMonitor.worked(1);
				}
			} catch (OperationCanceledException e) {
				// Only this component is reverted: another component's work is
				// none of this one's business. A failed revert is logged
				// rather than thrown, so it cannot replace the cancellation.
				tryMakeConsistent(pc);
				throw e;
			}
		}, pc.getSchedulingRule(), null);
	}

	private static boolean processPo(IProofComponent pc, IPSStatus status,
			ITactic tactic, SubMonitor pm) throws RodinDBException {

		final String poName = status.getElementName();
		if (pc.getProofAttempt(poName, REC_AUTO) != null) {
			// another attempt for REC_AUTO exists: don't process this PO
			return false;
		}

		pm.beginTask(poName + ":", 10); //$NON-NLS-1$

		pm.subTask(Messages.progress_RecalculateAutoStatus_loading);
		final IProofAttempt pa = pc.createProofAttempt(poName, REC_AUTO, pm.newChild(1));
		boolean committed = false;
		try {
			
			final IProofTree autoProofTree = pa.getProofTree();

			pm.subTask(Messages.progress_RecalculateAutoStatus_proving);
			tactic.apply(autoProofTree.getRoot(), new ProofMonitor(pm.newChild(7)));

			pm.subTask(Messages.progress_RecalculateAutoStatus_saving);
			// Update the tree if it was discharged
			if (autoProofTree.isClosed()) {
				pa.commit(false, getSimplifyProofPref(), pm.newChild(2));
				committed = true;
				
				if (DEBUG) {
					if (status.getHasManualProof()) {
						System.out.println("Proof " + poName + " is now automatic."); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
			} else {
				if (DEBUG) {
					if (!status.getHasManualProof()) {
						System.out.println("Proof " + poName + " is now manual."); //$NON-NLS-1$ //$NON-NLS-2$
					}
				}
				
				status.setHasManualProof(true, null);
			}
		} finally {
			pa.dispose();
			pm.done();
		}
		return committed;
	}

	private static ITactic autoTactic(IEventBRoot poRoot) {
		return EventBPlugin.getAutoPostTacticManager().getSelectedAutoTactics(
				poRoot);
	}

}
