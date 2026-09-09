/*******************************************************************************
 * Copyright (c) 2026 ISP RAS and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     ISP RAS - initial API and implementation
 *******************************************************************************/
package fr.systerel.internal.explorer.navigator;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.ResourceUtil;
import org.eclipse.ui.navigator.ILinkHelper;
import org.eclipse.ui.part.FileEditorInput;
import org.eventb.core.IPSStatus;
import org.rodinp.core.IOpenable;
import org.rodinp.core.IRodinElement;
import org.rodinp.core.IRodinFile;
import org.rodinp.core.RodinCore;

import fr.systerel.explorer.IElementNode;
import fr.systerel.internal.explorer.model.IModelElement;

/**
 * Links the Event-B Explorer with the editors, for the platform "Link with
 * Editor" toggle of the Common Navigator Framework.
 * <p>
 * Explorer to editor: the editor already open on the selected element's
 * component is brought to the front and told to select that element, which
 * moves the caret onto it. An editor is never opened here; opening a component
 * is what double-clicking it does.
 * </p>
 * <p>
 * Editor to explorer: the framework asks for a selection only when an editor is
 * activated, so the explorer follows at component granularity.
 * </p>
 */
public class EventBLinkHelper implements ILinkHelper {

	@Override
	public void activateEditor(IWorkbenchPage page, IStructuredSelection sel) {
		if (sel == null || sel.isEmpty()) {
			return;
		}
		final IRodinElement element = asRodinElement(sel.getFirstElement());
		if (element == null || element instanceof IPSStatus) {
			// Proof obligations belong to the Prover UI, which is driven by
			// the explorer's own open action.
			return;
		}
		final IOpenable openable = element.getOpenable();
		if (!(openable instanceof IRodinFile)) {
			// A project, or something with no file of its own.
			return;
		}
		final IRodinFile file = (IRodinFile) openable;
		final IEditorPart editor = page.findEditor(new FileEditorInput(file
				.getResource()));
		if (editor == null) {
			return;
		}
		page.bringToTop(editor);
		if (element.isRoot()) {
			// The whole component: bringing its editor forward is all there
			// is to do.
			return;
		}
		final ISelectionProvider provider = editor.getSite()
				.getSelectionProvider();
		if (provider != null) {
			provider.setSelection(new StructuredSelection(element));
		}
	}

	@Override
	public IStructuredSelection findSelection(IEditorInput input) {
		final IFile file = ResourceUtil.getFile(input);
		if (file == null) {
			return StructuredSelection.EMPTY;
		}
		final IRodinFile rodinFile = RodinCore.valueOf(file);
		if (rodinFile == null) {
			return StructuredSelection.EMPTY;
		}
		return new StructuredSelection(rodinFile.getRoot());
	}

	/*
	 * Maps a node of the explorer tree to the Rodin element it stands for, or
	 * returns null when it stands for none.
	 */
	private static IRodinElement asRodinElement(Object node) {
		if (node instanceof IElementNode) {
			// A grouping node ("Events", "Invariants", ...) has no counterpart
			// in the editor; point at the component holding it.
			return ((IElementNode) node).getParent();
		}
		if (node instanceof IModelElement) {
			return ((IModelElement) node).getInternalElement();
		}
		if (node instanceof IFile) {
			final IRodinFile file = RodinCore.valueOf((IFile) node);
			return file == null ? null : file.getRoot();
		}
		if (node instanceof IRodinFile) {
			return ((IRodinFile) node).getRoot();
		}
		if (node instanceof IRodinElement) {
			return (IRodinElement) node;
		}
		return null;
	}

}
