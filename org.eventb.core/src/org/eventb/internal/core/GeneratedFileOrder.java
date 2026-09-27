/*******************************************************************************
 * Copyright (c) 2026 Contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eventb.internal.core;

import java.util.Arrays;
import java.util.Comparator;

import org.eventb.core.IPOIdentifier;
import org.eventb.core.ISCCarrierSet;
import org.eventb.core.ISCConstant;
import org.eventb.core.ISCParameter;
import org.eventb.core.ISCVariable;
import org.eventb.internal.core.pom.ElementSorter;
import org.rodinp.core.IElementType;
import org.rodinp.core.IInternalElement;
import org.rodinp.core.IRodinElement;
import org.rodinp.core.RodinDBException;

/** Gives generated declarations a stable order before delta comparison and saving. */
public final class GeneratedFileOrder {

	private static final Comparator<IInternalElement> BY_NAME = Comparator
			.comparing(IInternalElement::getElementName)
			.thenComparing(element -> element.getElementType().getId());

	private GeneratedFileOrder() {
		// Utility class.
	}

	public static void canonicalize(IInternalElement parent) throws RodinDBException {
		final IRodinElement[] children = parent.getChildren();
		for (int i = 0; i < children.length;) {
			if (!isGeneratedDeclaration(children[i])) {
				i++;
				continue;
			}
			int end = i + 1;
			while (end < children.length && isGeneratedDeclaration(children[end])) {
				end++;
			}
			sortRun(parent, children, i, end);
			i = end;
		}
		for (IRodinElement child : children) {
			if (child instanceof IInternalElement) {
				canonicalize((IInternalElement) child);
			}
		}
	}

	private static boolean isGeneratedDeclaration(IRodinElement element) {
		// Other children keep their source or producer order.
		final IElementType<?> type = element.getElementType();
		return type == ISCCarrierSet.ELEMENT_TYPE
				|| type == ISCConstant.ELEMENT_TYPE
				|| type == ISCVariable.ELEMENT_TYPE
				|| type == ISCParameter.ELEMENT_TYPE
				|| type == IPOIdentifier.ELEMENT_TYPE;
	}

	private static void sortRun(IInternalElement parent, IRodinElement[] children,
			int start, int end) throws RodinDBException {
		if (end - start < 2) {
			return;
		}
		final IInternalElement[] original = new IInternalElement[end - start];
		for (int i = start; i < end; i++) {
			original[i - start] = (IInternalElement) children[i];
		}
		final IInternalElement[] ordered = original.clone();
		Arrays.sort(ordered, BY_NAME);
		if (Arrays.equals(original, ordered)) {
			return;
		}
		final ElementSorter<IInternalElement> sorter = new ElementSorter<>();
		for (IInternalElement element : ordered) {
			sorter.addItem(element);
		}
		// A run can be followed by another kind of element; keep that boundary.
		final IRodinElement afterRun = end < children.length ? children[end] : null;
		sorter.sort(original, (element, next) -> element.move(parent,
				next != null ? next : afterRun, null, false, null));
	}
}
