/*******************************************************************************
 * Copyright (c) 2000, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     ETH Zurich - adapted from org.eclipse.jdt.internal.core.DeltaProcessingState
 *******************************************************************************/
package org.rodinp.internal.core;

import java.util.Arrays;

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.rodinp.core.IElementChangedListener;
import org.rodinp.core.IRodinProject;

/**
 * Keep the global states used during Rodin element delta processing.
 */
public class DeltaProcessingState implements IResourceChangeListener {
	
	/**
	 * Immutable snapshot of the registered listeners.
	 *
	 * Listeners and masks must be read together: a notifier that paired one
	 * array with a stale copy of the other would mismatch them. Holding both in
	 * one object published through a single volatile field gives readers an
	 * atomic, correctly visible snapshot, and keeps the copy-on-write behaviour
	 * the notification code relies on. This is the contract of
	 * org.eclipse.core.runtime.ListenerList, which cannot be used here because
	 * it stores no per-listener mask and would not update the mask of a
	 * listener that registers twice.
	 */
	static final class Listeners {
		final IElementChangedListener[] listeners;
		final int[] masks;

		Listeners(IElementChangedListener[] listeners, int[] masks) {
			this.listeners = listeners;
			this.masks = masks;
		}
	}

	/*
	 * Collection of listeners for Rodin element deltas. Written only while
	 * holding this object's monitor, read without locking.
	 */
	private volatile Listeners elementChangedListeners = new Listeners(
			new IElementChangedListener[0], new int[0]);

	/**
	 * Returns the currently registered listeners. The result is a consistent
	 * snapshot and never changes afterwards, so a notification in progress is
	 * unaffected by concurrent registrations.
	 */
	public Listeners getElementChangedListeners() {
		return elementChangedListeners;
	}
	
	/*
	 * The delta processor for the current thread.
	 */
	private ThreadLocal<DeltaProcessor> deltaProcessors = new ThreadLocal<DeltaProcessor>();
	
	/**
	 * This is a cache of the projects before any project addition/deletion has started.
	 */
	// TODO move dbProjectsCache in DeltaProcessor, so that it's ThreadLocal!
	public IRodinProject[] dbProjectsCache;
	
	/*
	 * Registration always publishes a fresh snapshot rather than mutating the
	 * arrays in place, so a notification already under way keeps iterating the
	 * list it started with -- including when a listener deregisters itself from
	 * inside its own callback.
	 */
	public synchronized void addElementChangedListener(IElementChangedListener listener, int eventMask) {
		final Listeners current = elementChangedListeners;
		final int count = current.listeners.length;
		for (int i = 0; i < count; i++) {
			if (current.listeners[i].equals(listener)) {
				// Already registered: only the mask changes.
				final int[] newMasks = current.masks.clone();
				newMasks[i] = eventMask;
				elementChangedListeners = new Listeners(current.listeners,
						newMasks);
				return;
			}
		}
		final IElementChangedListener[] newListeners = Arrays.copyOf(
				current.listeners, count + 1);
		final int[] newMasks = Arrays.copyOf(current.masks, count + 1);
		newListeners[count] = listener;
		newMasks[count] = eventMask;
		elementChangedListeners = new Listeners(newListeners, newMasks);
	}

	public DeltaProcessor getDeltaProcessor() {
		DeltaProcessor deltaProcessor = this.deltaProcessors.get();
		if (deltaProcessor != null) return deltaProcessor;
		deltaProcessor = new DeltaProcessor(this, RodinDBManager.getRodinDBManager());
		this.deltaProcessors.set(deltaProcessor);
		return deltaProcessor;
	}

	public synchronized void removeElementChangedListener(IElementChangedListener listener) {
		final Listeners current = elementChangedListeners;
		final int count = current.listeners.length;
		for (int i = 0; i < count; i++) {
			if (current.listeners[i].equals(listener)) {
				final IElementChangedListener[] newListeners = new IElementChangedListener[count - 1];
				final int[] newMasks = new int[count - 1];
				System.arraycopy(current.listeners, 0, newListeners, 0, i);
				System.arraycopy(current.masks, 0, newMasks, 0, i);
				final int trailing = count - i - 1;
				if (trailing > 0) {
					System.arraycopy(current.listeners, i + 1, newListeners, i, trailing);
					System.arraycopy(current.masks, i + 1, newMasks, i, trailing);
				}
				elementChangedListeners = new Listeners(newListeners, newMasks);
				return;
			}
		}
	}

	@Override
	public void resourceChanged(IResourceChangeEvent event) {
		boolean isPostChange = event.getType() == IResourceChangeEvent.POST_CHANGE;
		try {
			getDeltaProcessor().resourceChanged(event);
		} finally {
			// TODO (jerome) see 47631, may want to get rid of following so as to reuse delta processor ? 
			if (isPostChange) {
				this.deltaProcessors.set(null);
			}
		}

	}

}
