/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.debug.ui.actions;


import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.ui.DebugUITools;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.jdt.debug.core.IJavaBreakpoint;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaExceptionBreakpoint;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.jdt.debug.core.IJavaWatchpoint;
import org.eclipse.jdt.internal.debug.ui.IJavaDebugHelpContextIds;
import org.eclipse.jdt.internal.debug.ui.JDIDebugUIPlugin;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ILabelProvider;
import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ListSelectionDialog;

/**
 * Action to associate an object with one or more breakpoints.
 */
public class InstanceFiltersAction extends ObjectActionDelegate {
	
	class InstanceFilterDialog extends ListSelectionDialog {
		
		public InstanceFilterDialog(
			Shell parentShell,
			Object input,
			IStructuredContentProvider contentProvider,
			ILabelProvider labelProvider,
			String message) {
			super(parentShell, input, contentProvider, labelProvider, message);
		}
		
		

		/* (non-Javadoc)
		 * @see org.eclipse.jface.dialogs.Dialog#createDialogArea(org.eclipse.swt.widgets.Composite)
		 */
		protected Control createDialogArea(Composite parent) {
			Control control = super.createDialogArea(parent);
			PlatformUI.getWorkbench().getHelpSystem().setHelp(
				parent,
				IJavaDebugHelpContextIds.INSTANCE_BREAKPOINT_SELECTION_DIALOG);				
			return control;
			
		}

}

	/**
	 * @see org.eclipse.ui.IActionDelegate#run(IAction)
	 */
	public void run(IAction action) {
		IStructuredSelection selection = getCurrentSelection();
		if (selection == null || selection.size() > 1) {
			return;
		}
		
		Object o = selection.getFirstElement();
		if (o instanceof IJavaVariable) {
			final IJavaVariable var = (IJavaVariable)o;
			try {
				IValue value = var.getValue();
				if (value instanceof IJavaObject) {
					final IJavaObject object = (IJavaObject)value;
					final List breakpoints = getApplicableBreakpoints(var, object);
					IStructuredContentProvider content = new IStructuredContentProvider() {
						public void dispose() {}
						
						public Object[] getElements(Object input) {
							return breakpoints.toArray();
						}
						
						public void inputChanged(Viewer viewer, Object a, Object b) {}
					};
					final IDebugModelPresentation modelPresentation= DebugUITools.newDebugModelPresentation();
					ListSelectionDialog dialog = new InstanceFilterDialog(JDIDebugUIPlugin.getActiveWorkbenchShell(), breakpoints, content, modelPresentation, MessageFormat.format(ActionMessages.InstanceFiltersAction_1, new String[] {var.getName()})){ 
						public void okPressed() {
							// check if breakpoints have already been restricted to other objects.
							Object[] checkBreakpoint= getViewer().getCheckedElements();
							for (int k= 0; k < checkBreakpoint.length; k++) {
								IJavaBreakpoint breakpoint= (IJavaBreakpoint) checkBreakpoint[k];
								try {
									IJavaObject[] instanceFilters= breakpoint.getInstanceFilters();
									boolean sameTarget = false;
									for (int i = 0; i < instanceFilters.length; i++) {
										IJavaObject instanceFilter = instanceFilters[i];
										if (instanceFilter.getDebugTarget().equals(object.getDebugTarget())) {
											sameTarget = true;
											break;
										}
									}
									if (sameTarget) {
										MessageDialog messageDialog= new MessageDialog(JDIDebugUIPlugin.getActiveWorkbenchShell(), ActionMessages.InstanceFiltersAction_2, 
											null, MessageFormat.format(ActionMessages.InstanceFiltersAction_3, new String[] { modelPresentation.getText(breakpoint), var.getName()}), 
											MessageDialog.QUESTION, new String[] { ActionMessages.InstanceFiltersAction_Yes_2, ActionMessages.InstanceFiltersAction_Cancel_3}, // 
											0);
										if (messageDialog.open() == Window.OK) {
											for (int i= 0; i < instanceFilters.length; i++) {
												breakpoint.removeInstanceFilter(instanceFilters[i]);
											}
										} else {
											// if 'cancel', do not close the instance filter dialog
											return;
										}
									}
								} catch (CoreException e) {
									JDIDebugUIPlugin.log(e);
								}
							}
							super.okPressed();
						}
					};
					dialog.setTitle(ActionMessages.InstanceFiltersAction_2); 
					
					// determine initial selection
					List existing = new ArrayList();
					Iterator iter = breakpoints.iterator();
					while (iter.hasNext()) {
						IJavaBreakpoint bp = (IJavaBreakpoint)iter.next();
						IJavaObject[] filters = bp.getInstanceFilters();
						for (int i = 0; i < filters.length; i++) {
							if (filters[i].equals(object)) {
								existing.add(bp);
								break;
							}
						}
					}
					dialog.setInitialSelections(existing.toArray());
					
					if (dialog.open() == Window.OK) {
						Object[] selectedBreakpoints = dialog.getResult();
						if (selectedBreakpoints != null) {
							// add
							for (int i = 0; i < selectedBreakpoints.length; i++) {
								IJavaBreakpoint bp = (IJavaBreakpoint)selectedBreakpoints[i];
								bp.addInstanceFilter(object);
								existing.remove(bp);
							}
							// remove
							iter = existing.iterator();
							while (iter.hasNext()) {
								IJavaBreakpoint bp = (IJavaBreakpoint)iter.next();
								bp.removeInstanceFilter(object);
							}
						}
					}
				} else {
					// only allowed for objects
				}
			} catch (CoreException e) {
				JDIDebugUIPlugin.log(e);
			}
		}
	}
	
	protected List getApplicableBreakpoints(IJavaVariable variable, IJavaObject object) {
		List breakpoints = new ArrayList();
		
		try {
			// collect names in type hierarchy
			List superTypeNames = new ArrayList();
			IJavaType type = object.getJavaType();
			while (type instanceof IJavaClassType) {
				superTypeNames.add(type.getName());
				type = ((IJavaClassType)type).getSuperclass();
			}
			
			IBreakpoint[] allBreakpoints = DebugPlugin.getDefault().getBreakpointManager().getBreakpoints();
			for (int i = 0; i < allBreakpoints.length; i++) {
				if (allBreakpoints[i] instanceof IJavaBreakpoint) {
					IJavaBreakpoint jbp = (IJavaBreakpoint)allBreakpoints[i];
					IJavaBreakpoint valid = null;
					if (jbp instanceof IJavaWatchpoint && variable instanceof IJavaFieldVariable) {
						IJavaWatchpoint wp = (IJavaWatchpoint)jbp;
						IJavaFieldVariable fv = (IJavaFieldVariable)variable;
						if (variable.getName().equals(wp.getFieldName()) && fv.getDeclaringType().getName().equals(wp.getTypeName())) {
							valid = wp;
						}
					} else if (superTypeNames.contains(jbp.getTypeName()) || jbp instanceof IJavaExceptionBreakpoint) {
						valid = jbp;
					}
					if (valid != null && valid.supportsInstanceFilters()) {
						breakpoints.add(valid);
					}
				}
			}
		} catch (CoreException e) {
			JDIDebugUIPlugin.log(e);
		}
		 
		return breakpoints;
	}

}
