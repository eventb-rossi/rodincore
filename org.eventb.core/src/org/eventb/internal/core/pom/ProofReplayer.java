/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - replaying proofs of several components at once
 *******************************************************************************/
package org.eventb.internal.core.pom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eventb.core.EventBPlugin;
import org.eventb.core.IPRProof;
import org.eventb.core.IPSRoot;
import org.eventb.core.IPSStatus;
import org.eventb.internal.core.pom.ProvingPool.ComponentTask;

/**
 * Replays the stored proofs of undischarged obligations.
 * <p>
 * Grouped by proof component like the rest of the proving work, so that
 * obligations of one component are replayed in turn under that component's
 * scheduling rule while different components proceed at the same time.
 * </p>
 */
public class ProofReplayer {

	private ProofReplayer() {
		// Utility class.
	}

	public static void rebuildProofs(Set<IPSStatus> statuses,
			IProgressMonitor monitor) throws CoreException {
		try {
			ProvingPool.runAll(componentTasks(statuses), monitor);
		} finally {
			monitor.done();
		}
	}

	/*
	 * One task per proof component: obligations of one component stay ordered
	 * and share its scheduling rule, while different components proceed at the
	 * same time.
	 */
	private static List<ComponentTask> componentTasks(Set<IPSStatus> statuses) {
		final List<ComponentTask> tasks = new ArrayList<ComponentTask>();
		for (final List<IPSStatus> group : ProvingPool.groupByComponent(
				statuses).values()) {
			tasks.add(m -> replay(group, m));
		}
		return tasks;
	}

	/*
	 * Unlike the other proving commands there is no save to hoist here: each
	 * proof is committed and saved by the rebuild itself.
	 */
	private static void replay(List<IPSStatus> statuses,
			IProgressMonitor monitor) throws CoreException {
		final SubMonitor sMonitor = SubMonitor.convert(monitor, statuses.size());
		for (final IPSStatus status : statuses) {
			if (sMonitor.isCanceled()) {
				return;
			}
			final IPRProof proof = status.getProof();
			EventBPlugin.rebuildProof(proof, true, sMonitor.split(1));
		}
	}

}
