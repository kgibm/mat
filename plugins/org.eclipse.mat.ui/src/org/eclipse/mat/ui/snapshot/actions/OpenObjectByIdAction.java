/*******************************************************************************
 * Copyright (c) 2008, 2021 SAP AG and IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    Andrew Johnson (IBM Corporation) - unindexed objects
 *******************************************************************************/
package org.eclipse.mat.ui.snapshot.actions;

import java.math.BigInteger;
import java.util.regex.Pattern;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.OQL;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.ObjectReference;
import org.eclipse.mat.ui.MemoryAnalyserPlugin;
import org.eclipse.mat.ui.Messages;
import org.eclipse.mat.ui.QueryExecution;
import org.eclipse.mat.ui.snapshot.editor.HeapEditor;
import org.eclipse.mat.ui.snapshot.editor.ISnapshotEditorInput;
import org.eclipse.mat.util.MessageUtil;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

public class OpenObjectByIdAction extends Action
{

    public OpenObjectByIdAction()
    {
        super(Messages.OpenObjectByIdAction_FindObjectByAddress, MemoryAnalyserPlugin
                        .getImageDescriptor(MemoryAnalyserPlugin.ISharedImages.FIND));
    }

    @Override
    public void run()
    {
        IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        IEditorPart part = page == null ? null : page.getActiveEditor();

        if (part instanceof HeapEditor)
        {
            HeapEditor editor = (HeapEditor) part;

            String value = askForAddress();

            if (value != null)
            {
                retrieveObjectAndOpenPane(editor, value);
            }
        }
    }

    private void retrieveObjectAndOpenPane(HeapEditor editor, String value)
    {
        String errorMessage = null;

        try
        {
            // Long.parseLong works only for positive hex
            long objectAddress = new BigInteger(value.substring(2), 16).longValue();
            ISnapshot snapshot = ((ISnapshotEditorInput) editor.getPaneEditorInput()).getSnapshot();
            if (snapshot == null)
            {
                errorMessage = Messages.OpenObjectByIdAction_ErrorGettingHeapDump;
            }
            else
            {
                int objectId;
                try
                {
                    objectId = snapshot.mapAddressToId(objectAddress);
                }
                catch (SnapshotException e)
                {
                    objectId = -1;
                }
                if (objectId < 0)
                {
                    ObjectReference ref = new ObjectReference(snapshot, objectAddress);
                    try
                    {
                        // Check the object is valid, though unindexed.
                        IObject obj = ref.getObject();
                        objectAddress = obj.getObjectAddress();
                    }
                    catch (SnapshotException e)
                    {
                        errorMessage = MessageUtil.format(Messages.OpenObjectByIdAction_NoObjectWithAddress,
                                        new Object[] { value });
                    }
                    if (errorMessage == null)
                    {
                        QueryExecution.executeCommandLine(editor, null, "oql \"" + OQL.forAddress(objectAddress) + "\""); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
                else
                {
                    QueryExecution.executeCommandLine(editor, null, "list_objects " + value); //$NON-NLS-1$
                }
            }
        }
        catch (NumberFormatException e)
        {
            // $JL-EXC$
            errorMessage = Messages.OpenObjectByIdAction_AddressIsNotHexNumber;
        }
        catch (SnapshotException e)
        {
            // $JL-EXC$
            errorMessage = MessageUtil.format(Messages.OpenObjectByIdAction_ErrorReadingObject, new Object[] { e
                            .getMessage() });
        }

        if (errorMessage != null)
        {
            MessageDialog.openError(PlatformUI.getWorkbench().getDisplay().getActiveShell(),
                            Messages.OpenObjectByIdAction_ErrorOpeningObject, errorMessage);
        }
    }

    private String askForAddress()
    {
        final Pattern pattern = Pattern.compile("^0x\\p{XDigit}+$");//$NON-NLS-1$

        InputDialog dialog = new InputDialog(PlatformUI.getWorkbench().getDisplay().getActiveShell(),
                        Messages.OpenObjectByIdAction_FindObjectByAddress, Messages.OpenObjectByIdAction_ObjectAddress,
                        "0x", new IInputValidator() //$NON-NLS-1$
                        {

                            public String isValid(String newText)
                            {
                                return !pattern.matcher(newText).matches() ? Messages.OpenObjectByIdAction_AddressMustBeHexNumber
                                                : null;
                            }

                        });

        int result = dialog.open();

        String value = dialog.getValue();
        if (result == IDialogConstants.CANCEL_ID)
            return null;
        return value;
    }
}
