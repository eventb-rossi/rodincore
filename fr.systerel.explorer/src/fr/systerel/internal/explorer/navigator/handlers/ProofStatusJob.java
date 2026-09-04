/*******************************************************************************
 * Copyright (c) 2010, 2014 Systerel and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Systerel - initial API and implementation
 *******************************************************************************/
package fr.systerel.internal.explorer.navigator.handlers;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eventb.core.IPSStatus;
import org.rodinp.core.RodinCore;
import org.eventb.internal.ui.EventBUIExceptionHandler;
import org.eventb.internal.ui.EventBUIExceptionHandler.UserAwareness;

import fr.systerel.explorer.ExplorerPlugin;

/**
 * Abstract implementation of a workspace job running on proof statuses.
 */
public abstract class ProofStatusJob extends WorkspaceJob {

	private static final int PRECOMPUTE_WORK = 1;
	private static final int COMPUTE_WORK = 9;

	private final boolean pendingOnly;
	private final HandlerInput input;

	public ProofStatusJob(String name, boolean pendingOnly,
			IStructuredSelection selection) {
		super(name);
		this.pendingOnly = pendingOnly;
		this.input = new HandlerInput(selection);
		// Deliberately no scheduling rule on the job itself.
		//
		// The work is done one proof component at a time, on other threads,
		// and each of those takes its own component's rule. A rule here would
		// contain every one of them, so a worker asking for a component rule
		// from another thread would block against this thread -- which is
		// meanwhile waiting for that worker. Instead the only part that runs
		// on this thread, collecting the obligations, takes the rule itself.
	}

	@Override
	public IStatus runInWorkspace(IProgressMonitor monitor)
			throws CoreException {
		final SubMonitor subMonitor = SubMonitor.convert(monitor, getName(),
				PRECOMPUTE_WORK + COMPUTE_WORK);
		try {
			final Set<IPSStatus> statuses = collectStatuses(subMonitor
					.newChild(PRECOMPUTE_WORK));
			perform(statuses, subMonitor.newChild(COMPUTE_WORK));
		} catch (CoreException e) {
			final String message = "An exception occurred while running " + getName();
			EventBUIExceptionHandler.handleException(e, message,
					UserAwareness.INFORM, "Proof Status Job: ");
			return new Status(Status.ERROR, ExplorerPlugin.PLUGIN_ID,
					message, e);
		} catch (InterruptedException e) {
			// set and propagate the interrupt status above
			Thread.currentThread().interrupt();
			// canceled: return as soon as possible
			return Status.CANCEL_STATUS;
		} finally {
			subMonitor.done();
		}
		return Status.OK_STATUS;
	}

	/*
	 * Reads the workspace, so it holds the rule covering everything selected
	 * while it does. That rule is released before the proving starts, which is
	 * what lets each component be proved under a rule of its own.
	 */
	private Set<IPSStatus> collectStatuses(SubMonitor monitor)
			throws CoreException, InterruptedException {
		final AtomicReference<Set<IPSStatus>> result = new AtomicReference<Set<IPSStatus>>();
		// IWorkspaceRunnable may only throw CoreException, so the interruption
		// is carried out rather than thrown from inside the runnable.
		final AtomicReference<InterruptedException> interrupted = new AtomicReference<InterruptedException>();
		RodinCore.run(m -> {
			try {
				result.set(input.getStatuses(pendingOnly, monitor));
			} catch (InterruptedException e) {
				interrupted.set(e);
			}
		}, input.getSchedulingRule(), monitor);
		if (interrupted.get() != null) {
			throw interrupted.get();
		}
		return result.get();
	}

	protected abstract void perform(Set<IPSStatus> statuses, SubMonitor monitor)
			throws CoreException, InterruptedException;

}
