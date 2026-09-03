/*******************************************************************************
 * Copyright (c) 2026 Rossi and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Rossi - preference for the interactive tactic
 *******************************************************************************/
package org.eventb.internal.core.pom;

import org.eventb.core.seqprover.ITacticDescriptor;
import org.eventb.core.seqprover.autoTacticPreference.AutoTacticPreference;

/**
 * The tactic run when the user asks for automatic provers from within the
 * prover, as opposed to the one the builder runs in the background.
 * <p>
 * This is deliberately a separate object from {@link POMTacticPreference}: the
 * two preferences select independently, and sharing one instance meant choosing
 * an interactive profile silently changed background proving as well.
 * </p>
 */
public class InterTacticPreference extends AutoTacticPreference {

	private static InterTacticPreference instance;

	private InterTacticPreference() {
		// Singleton: private default constructor
		super();
	}

	public static InterTacticPreference getDefault() {
		if (instance == null)
			instance = new InterTacticPreference();
		return instance;
	}

	@Override
	public ITacticDescriptor getDefaultDescriptor() {
		// Same starting point as the automatic tactic, so that a user who never
		// picks an interactive profile keeps the behaviour they had.
		return POMTacticPreference.getDefault().getDefaultDescriptor();
	}

}
