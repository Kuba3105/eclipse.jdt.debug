/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.debug.ui.monitors;


import org.eclipse.debug.core.model.IDebugElement;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IViewActionDelegate;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.texteditor.IUpdate;

public abstract class MonitorAction implements IViewActionDelegate, IUpdate {

	protected MonitorsView fView;
	protected IAction fAction;
	
	/**
	 * Returns the current selection in the debug view or <code>null</code>
	 * if there is no selection.
	 * 
	 * @return IStructuredSelection
	 */
	protected IStructuredSelection getDebugViewSelection() {
		if (fView != null) {
			ISelection s =fView.getViewSite().getPage().getSelection(IDebugUIConstants.ID_DEBUG_VIEW);
			
			if (s instanceof IStructuredSelection) {
				return (IStructuredSelection)s;
			}
		}
		return null;
	}
	
	protected IJavaDebugTarget getDebugTarget() {
		IStructuredSelection ss= getDebugViewSelection();
		if (ss.isEmpty() || ss.size() > 1) {
			return null;
		}
		Object element= ss.getFirstElement();
		if (element instanceof IDebugElement) {
			return (IJavaDebugTarget)((IDebugElement)element).getDebugTarget().getAdapter(IJavaDebugTarget.class);
		}
		
		return null;
	}
	
	/**
	 * @see org.eclipse.ui.IViewActionDelegate#init(IViewPart)
	 */
	public void init(IViewPart view) {
		fView= (MonitorsView)view;
		fView.add(this);
	}

	/**
	 * @see org.eclipse.ui.IActionDelegate#selectionChanged(IAction, ISelection)
	 */
	public void selectionChanged(IAction action, ISelection selection) {
		fAction= action;
	}
}