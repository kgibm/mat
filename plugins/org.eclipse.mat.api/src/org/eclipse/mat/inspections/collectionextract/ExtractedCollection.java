/*******************************************************************************
 * Copyright (c) 2008, 2021 SAP AG, IBM Corporation and others
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    IBM Corporation - enhancements and fixes
 *    James Livingston - expose collection utils as API
 *******************************************************************************/
package org.eclipse.mat.inspections.collectionextract;

import java.util.Iterator;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.model.IObject;

/**
 * An class representing a collection extracted from the heap. It provides
 * convenience methods for querying certain properties of the collection (e.g.
 * size) and for extracting the elements of the collection
 * 
 * @since 1.5
 */
public class ExtractedCollection extends AbstractExtractedCollection<IObject, ICollectionExtractor>
{

    private static final long serialVersionUID = 1L;

    public ExtractedCollection(IObject coll, ICollectionExtractor extractor)
    {
        super(coll, extractor);
    }

    public Iterator<IObject> iterator()
    {
        try
        {
            final int[] array = getExtractor().extractEntryIds(getCollection());
            return new Iterator<IObject>()
            {
                final ISnapshot snapshot = getCollection().getSnapshot();
                int index = 0;

                public boolean hasNext()
                {
                    return index < array.length;
                }

                public IObject next()
                {
                    try
                    {
                        return snapshot.getObject(array[index++]);
                    }
                    catch (SnapshotException e)
                    {
                        throw new RuntimeException(e);
                    }
                }

                public void remove()
                {
                    throw new UnsupportedOperationException();
                }
            };
        }
        catch (SnapshotException e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get the number of non-null elements contained in the collection
     * 
     * @return the number of non-null elements
     * @throws SnapshotException
     */
    public Integer getNumberOfNotNullElements() throws SnapshotException
    {
        return getExtractor().getNumberOfNotNullElements(getCollection());
    }
}
