/*******************************************************************************
 * Copyright (c) 2010, 2021 Systerel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Systerel - initial API and implementation
 *     ISP RAS - parallelize code
 *******************************************************************************/
package fr.systerel.internal.explorer.navigator.handlers;

import static org.eventb.core.EventBPlugin.rebuildProof;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eventb.core.IPRProof;
import org.eventb.core.IPSStatus;

import fr.systerel.internal.explorer.navigator.actionProviders.Messages;

/**
 * Handler for the 'Replay Proofs of Undischarged POs' command.
 */
public class ReplayUndischargedHandler extends AbstractJobHandler {

	@Override
	protected WorkspaceJob getWorkspaceJob(IStructuredSelection sel) {
		return new ParallelProofStatusJob(Messages.dialogs_replayingProofs, true, sel) {

			@Override
			protected void perform(Set<IPSStatus> statuses,
					SubMonitor subMonitor) throws InterruptedException, CoreException {
				rebuildProofs(statuses, subMonitor);
			}
		};
	}

	static void rebuildProofs(Set<IPSStatus> statuses, IProgressMonitor monitor)
			throws InterruptedException, CoreException {
		final SubMonitor subMonitor = SubMonitor.convert(monitor,
				statuses.size());

		int cores = Runtime.getRuntime().availableProcessors();
		final ThreadPoolExecutor executor =
				(ThreadPoolExecutor) Executors.newFixedThreadPool(Math.min(statuses.size(), cores));

		try {
			for (IPSStatus status : statuses) {
				Runnable task = () -> {
					try {
						if (subMonitor != null && subMonitor.isCanceled()) {
							return;
						}
						final IPRProof proof = status.getProof();
						rebuildProof(proof, true, subMonitor.newChild(1));
					} catch (Exception ex) {
						Thread t = Thread.currentThread();
						t.getUncaughtExceptionHandler().uncaughtException(t, ex);
					}
				};

				executor.submit(task);
			}

			executor.shutdown();
			while (!executor.isTerminated()) {
				executor.awaitTermination(1, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
