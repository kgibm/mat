/*******************************************************************************
 * Copyright (c) 2008, 2022 SAP AG, IBM Corporation and others
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
package org.eclipse.mat.inspections.collections;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.inspections.InspectionAssert;
import org.eclipse.mat.inspections.collectionextract.CollectionExtractionUtils;
import org.eclipse.mat.inspections.collectionextract.ExtractedMap;
import org.eclipse.mat.inspections.collectionextract.IMapExtractor;
import org.eclipse.mat.internal.Messages;
import org.eclipse.mat.internal.collectionextract.HashMapCollectionExtractor;
import org.eclipse.mat.query.Column;
import org.eclipse.mat.query.ContextProvider;
import org.eclipse.mat.query.IContextObject;
import org.eclipse.mat.query.IContextObjectSet;
import org.eclipse.mat.query.IQuery;
import org.eclipse.mat.query.IResultTable;
import org.eclipse.mat.query.ResultMetaData;
import org.eclipse.mat.query.annotations.Argument;
import org.eclipse.mat.query.annotations.CommandName;
import org.eclipse.mat.query.annotations.HelpUrl;
import org.eclipse.mat.query.annotations.Icon;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.OQL;
import org.eclipse.mat.snapshot.extension.Subjects;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.query.IHeapObjectArgument;
import org.eclipse.mat.util.IProgressListener;

@CommandName("hash_entries")
@Icon("/META-INF/icons/hash_map.gif")
@HelpUrl("/org.eclipse.mat.ui.help/tasks/analyzingjavacollectionusage.html")
@Subjects({"java.util.AbstractMap",
    "java.util.jar.Attributes",
    "java.util.Dictionary",
    "java.lang.ThreadLocal$ThreadLocalMap",
    "java.util.concurrent.ConcurrentHashMap$Segment",
    "java.util.concurrent.ConcurrentHashMap$CollectionView",
    "java.util.Collections$SynchronizedMap",
    "java.util.Collections$UnmodifiableMap",
    "java.util.Collections$CheckedMap",
    "java.util.ImmutableCollections$AbstractImmutableMap",
    "java.util.ResourceBundle",
    "java.awt.RenderingHints",
    "sun.awt.WeakIdentityHashMap",
    "javax.script.SimpleBindings",
    "javax.management.openmbean.TabularDataSupport",
    "com.ibm.jvm.util.HashMapRT",
    "com.sap.engine.lib.util.AbstractDataStructure"
})
public class HashEntriesQuery implements IQuery
{
    private static final String NULL = "<null>"; //$NON-NLS-1$

    @Argument
    public ISnapshot snapshot;

    @Argument(flag = Argument.UNFLAGGED)
    public IHeapObjectArgument objects;

    @Argument(isMandatory = false)
    public String collection;

    @Argument(isMandatory = false)
    public String array_attribute;

    @Argument(isMandatory = false)
    public String key_attribute;

    @Argument(isMandatory = false)
    public String value_attribute;

    static class Entry
    {
        public Entry(int collectionId, String collectionName, int keyId, int valueId, Map.Entry<IObject, IObject> entry)
        {
            this.collectionId = collectionId;
            this.collectionName = collectionName;
            this.keyId = keyId;
            this.valueId = valueId;
            this.entry = entry;
        }

        int collectionId;
        int keyId;
        int valueId;
        Map.Entry<IObject, IObject> entry;

        String collectionName;
        String keyValue;
        String valueValue;
    }

    public static class Result implements IResultTable
    {
        private ISnapshot snapshot;
        private List<Entry> entries;
        private Map<String, Entry> key2entry;

        private Result(ISnapshot snapshot, List<Entry> entries)
        {
            this.snapshot = snapshot;
            this.entries = entries;
        }

        public ResultMetaData getResultMetaData()
        {
            return new ResultMetaData.Builder() //

                            .addContext(new ContextProvider(Messages.HashEntriesQuery_Column_Key)
                            {
                                public IContextObject getContext(Object row)
                                {
                                    return getKey(row);
                                }
                            }) //

                            .addContext(new ContextProvider(Messages.HashEntriesQuery_Column_Value)
                            {
                                public IContextObject getContext(Object row)
                                {
                                    return getValue(row);
                                }
                            }) //

                            .build();
        }

        public Column[] getColumns()
        {
            return new Column[] {
                            new Column(Messages.HashEntriesQuery_Column_Collection).sorting(Column.SortDirection.ASC), //
                            new Column(Messages.HashEntriesQuery_Column_Key), //
                            new Column(Messages.HashEntriesQuery_Column_Value) };
        }

        public Object getColumnValue(Object row, int columnIndex)
        {
            Entry entry = (Entry) row;

            switch (columnIndex)
            {
                case 0:
                    return entry.collectionName;
                case 1:
                    if (entry.keyValue == null)
                    {
                        if (entry.entry != null)
                            entry.keyValue = resolve(entry.entry.getKey());
                        else
                            entry.keyValue = resolve(entry.keyId);
                    }
                    return entry.keyValue;
                case 2:
                    if (entry.valueValue == null)
                    {
                        if (entry.entry != null)
                            entry.valueValue = resolve(entry.entry.getValue());
                        else
                            entry.valueValue = resolve(entry.valueId);
                    }
                    return entry.valueValue;
            }

            return null;
        }

        private String resolve(IObject object)
        {
            if (object == null)
                return null;
            String name = object.getClassSpecificName();

            if (name == null)
                name = object.getTechnicalName();

            return name;
        }

        private String resolve(int objectId)
        {
            try
            {
                if (objectId < 0)
                    return NULL;

                IObject object = snapshot.getObject(objectId);
                String name = resolve(object);
                return name;
            }
            catch (SnapshotException e)
            {
                throw new RuntimeException(e);
            }
        }

        public int getRowCount()
        {
            return entries.size();
        }

        public Object getRow(int rowId)
        {
            return entries.get(rowId);
        }

        public IContextObject getContext(final Object row)
        {
            return new IContextObject()
            {
                public int getObjectId()
                {
                    return ((Entry) row).collectionId;
                }
            };
        }

        private IContextObject getKey(Object row)
        {
            final int keyId = ((Entry) row).keyId;
            if (keyId >= 0)
            {
                return new IContextObject()
                {
                    public int getObjectId()
                    {
                        return keyId;
                    }
                };
            }
            else
            {
                if (((Entry) row).entry == null || (((Entry) row).entry).getKey() == null)
                    return null;
                return new IContextObjectSet() {

                    @Override
                    public int getObjectId()
                    {
                        return -1;
                    }

                    @Override
                    public int[] getObjectIds()
                    {
                        return new int[0];
                    }

                    @Override
                    public String getOQL()
                    {
                        java.util.Map.Entry<IObject, IObject> entry = ((Entry) row).entry;
                        if (entry != null)
                        {
                            if (entry.getKey() != null)
                                return OQL.forAddress(entry.getKey().getObjectAddress());
                        }
                        return null;
                    }
                };
            }
        }

        private IContextObject getValue(final Object row)
        {
            final int valueId = ((Entry) row).valueId;
            if (valueId >= 0)
            {
                return new IContextObject()
                {
                    public int getObjectId()
                    {
                        return valueId;
                    }
                };
            }
            else
            {
                if (((Entry) row).entry == null || (((Entry) row).entry).getValue() == null)
                    return null;
                return new IContextObjectSet() {

                    @Override
                    public int getObjectId()
                    {
                        return -1;
                    }

                    @Override
                    public int[] getObjectIds()
                    {
                        return new int[0];
                    }

                    @Override
                    public String getOQL()
                    {
                        java.util.Map.Entry<IObject, IObject> entry = ((Entry) row).entry;
                        if (entry != null)
                        {
                            if (entry.getValue() != null)
                                return OQL.forAddress(entry.getValue().getObjectAddress());
                        }
                        return null;
                    }
                };
            }
        }

        // //////////////////////////////////////////////////////////////
        // map-like getters
        // //////////////////////////////////////////////////////////////

        public synchronized String getString(String key, IProgressListener listener)
        {
            prepare(listener);

            Entry entry = key2entry.get(key);

            if (entry == null)
                return null;

            if (entry.valueValue == null)
                entry.valueValue = resolve(entry.valueId);

            return entry.valueValue == NULL ? null : entry.valueValue;
        }

        public synchronized int getObjectId(String key, IProgressListener listener)
        {
            prepare(listener);

            Entry entry = key2entry.get(key);

            if (entry == null)
                return -1;

            return entry.keyId;
        }

        private synchronized void prepare(IProgressListener listener)
        {
            if (key2entry != null)
                return;

            key2entry = new HashMap<String, Entry>();

            for (Entry entry : entries)
            {
                if (entry.keyValue == null)
                    entry.keyValue = resolve(entry.keyId);
                key2entry.put(entry.keyValue, entry);

                if (listener.isCanceled())
                    break;
            }
        }
    }

    public Result execute(IProgressListener listener) throws Exception
    {
        InspectionAssert.heapFormatIsNot(snapshot, "DTFJ-PHD"); //$NON-NLS-1$
        listener.subTask(Messages.HashEntriesQuery_Msg_Extracting);

        IMapExtractor specificExtractor;
        if (collection != null)
        {
            specificExtractor = new HashMapCollectionExtractor(null, array_attribute, key_attribute, value_attribute);
        }
        else
        {
            specificExtractor = null;
        }

        List<Entry> hashEntries = new ArrayList<Entry>();

        int counter = 0;
        IClass type = null;
        for (int[] objectIds : objects)
        {
            HashMapIntObject<List<Entry>> resultMap = null;
            int sortedObjs[] = objectIds;
            int prev = Integer.MIN_VALUE;
            for (int objectId : objectIds)
            {
                if (objectId < prev)
                {
                    sortedObjs = objectIds.clone();
                    Arrays.sort(sortedObjs);
                    resultMap = new HashMapIntObject<List<Entry>>();
                    break;
                }
                prev = objectId;
            }
            for (int objectId : sortedObjs)
            {
                if (listener.isCanceled())
                    break;

                IObject obj = snapshot.getObject(objectId);
                if (counter++ % 1000 == 0 && !obj.getClazz().equals(type))
                {
                    type = obj.getClazz();
                    listener.subTask(Messages.HashEntriesQuery_Msg_Extracting + "\n" + type.getName()); //$NON-NLS-1$
                }
                ExtractedMap map = CollectionExtractionUtils.extractMap(obj, collection, specificExtractor);

                if (map != null)
                {
                    List<Entry> hashEntries1 = resultMap != null ? new ArrayList<Entry>() : hashEntries;
                    for (Map.Entry<IObject, IObject> me : map)
                    {
                        Entry e;
                        int keyId,valueId;
                        Map.Entry<IObject, IObject> me2 = null;
                        try
                        {
                            keyId = (me.getKey() != null) ? me.getKey().getObjectId() : -1;
                        }
                        catch (RuntimeException e1)
                        {
                            keyId = -1;
                            me2 = me;
                        }
                        try
                        {
                            valueId = (me.getValue() != null) ? me.getValue().getObjectId() : -1;
                        }
                        catch (RuntimeException e1)
                        {
                            valueId = -1;
                            me2 = me;
                        }
                        if (me instanceof IObject)
                        {
                            IObject meObject = (IObject) me;
                            e = new Entry(obj.getObjectId(), obj.getDisplayName(), keyId, valueId, me2);
                        }
                        else
                        {
                            e = new Entry(objectId, obj.getDisplayName(), keyId, valueId, me2);
                        }
                        hashEntries1.add(e);
                    }
                    if (resultMap != null)
                        resultMap.put(objectId, hashEntries1);
                }
            }
            if (resultMap != null)
            {
                for (int objectId : objectIds)
                {
                    if (resultMap.containsKey(objectId))
                    {
                        hashEntries.addAll(resultMap.get(objectId));
                    }
                }
            }
            if (listener.isCanceled())
                break;
        }

        listener.done();
        return new Result(snapshot, hashEntries);
    }
}
