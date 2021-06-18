/*******************************************************************************
 * Copyright (c) 2021 ISP RAS and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     ISP RAS - initial API and implementation
 *******************************************************************************/
package fr.systerel.internal.explorer.navigator.handlers;

import org.eclipse.jface.viewers.IStructuredSelection;

/**
 * Abstract implementation of a workspace job running on proof statuses in parallel.
 */
public abstract class ParallelProofStatusJob extends ProofStatusJob {

	public ParallelProofStatusJob(String name, boolean pendingOnly,
			IStructuredSelection selection) {
		super(name, pendingOnly, selection);
		// Disable scheduling rule to parallelize proof status jobs
		setRule(null);
	}

}
