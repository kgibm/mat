/*******************************************************************************
 * Copyright (c) 2008, 2023 SAP AG and IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    SAP AG - initial API and implementation
 *    IBM Corporation - validation of indices
 *******************************************************************************/
package org.eclipse.mat.parser.internal;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.mat.SnapshotException;
import org.eclipse.mat.collect.HashMapIntObject;
import org.eclipse.mat.collect.IteratorInt;
import org.eclipse.mat.parser.IIndexBuilder;
import org.eclipse.mat.parser.internal.oql.OQLQueryImpl;
import org.eclipse.mat.parser.internal.util.ParserRegistry;
import org.eclipse.mat.parser.internal.util.ParserRegistry.Parser;
import org.eclipse.mat.parser.model.ClassImpl;
import org.eclipse.mat.parser.model.XGCRootInfo;
import org.eclipse.mat.parser.model.XSnapshotInfo;
import org.eclipse.mat.snapshot.IOQLQuery;
import org.eclipse.mat.snapshot.ISnapshot;
import org.eclipse.mat.snapshot.SnapshotFactory;
import org.eclipse.mat.snapshot.SnapshotFormat;
import org.eclipse.mat.snapshot.model.GCRootInfo;
import org.eclipse.mat.util.IProgressListener;
import org.eclipse.mat.util.IProgressListener.Severity;
import org.eclipse.mat.util.MessageUtil;
import org.eclipse.mat.util.SimpleMonitor;
import org.eclipse.mat.util.WrappedLoggingProgressListener;

public class SnapshotFactoryImpl implements SnapshotFactory.Implementation
{
    private static class SnapshotEntry
    {
        private int usageCount;
        private WeakReference<ISnapshot> snapshot;

        public SnapshotEntry(int usageCount, ISnapshot snapshot)
        {
            this.usageCount = usageCount;
            this.snapshot = new WeakReference<ISnapshot>(snapshot);
        }
    }

    private Map<File, SnapshotEntry> snapshotCache = new HashMap<File, SnapshotEntry>();

    public ISnapshot openSnapshot(File file, Map<String, String> args, IProgressListener listener) throws SnapshotException
    {
        WrappedLoggingProgressListener wrappedListener = new WrappedLoggingProgressListener(listener);
        ZonedDateTime start = ZonedDateTime.now();
        wrappedListener.sendUserMessage(
                        Severity.INFO, MessageUtil.format(Messages.SnapshotFactoryImpl_StartOpeningDump,
                                        file.getAbsolutePath(), DateTimeFormatter.ISO_ZONED_DATE_TIME.format(start)),
                        null);

        try
        {
            ISnapshot answer = null;

            // lookup in cache
            SnapshotEntry entry = snapshotCache.get(file);
            if (entry != null)
            {
                answer = entry.snapshot.get();

                if (answer != null)
                {
                    entry.usageCount++;
                    return answer;
                }
            }

            String name = file.getName();

            /*
             * Perhaps there are extensions with dots, e.g. .phd.gz or
             * .hprof.gz, so this code ensures the whole extension is removed.
             */
            IContentTypeManager contentTypeManager = Platform.getContentTypeManager();
            IContentType javaheapdump = contentTypeManager.getContentType("org.eclipse.mat.JavaHeapDump"); //$NON-NLS-1$
            List<IContentType> listtypes = new ArrayList<IContentType>();
            if (javaheapdump != null)
            {
                String n1 = name;
                IContentType types[];
                try (FileInputStream fis = new FileInputStream(file))
                {
                    types = contentTypeManager.findContentTypesFor(fis, file.getPath());
                    if (types.length == 0)
                    {
                        try (FileInputStream fis2 = new FileInputStream(file))
                        {
                            types = contentTypeManager.findContentTypesFor(fis2, null);
                        }
                    }
                }
                catch (IOException e)
                {
                    // Ignore, try using file name alone
                    types = contentTypeManager.findContentTypesFor(file.getPath());
                }
                for (IContentType tp : types)
                {
                    if (tp.isKindOf(javaheapdump))
                    {
                        // See if this content description is based on the file
                        // contents
                        IContentDescription cd1, cd2;
                        try (FileInputStream fis = new FileInputStream(file))
                        {
                            // Succeeds if based on context
                            cd1 = tp.getDescriptionFor(fis, IContentDescription.ALL);
                        }
                        catch (IOException e)
                        {
                            cd1 = null;
                        }
                        try (InputStream sr = new ByteArrayInputStream(new byte[10]))
                        {
                            // Succeeds if generic type without content checking
                            cd2 = tp.getDescriptionFor(sr, IContentDescription.ALL);
                        }
                        catch (IOException e)
                        {
                            cd2 = null;
                        }
                        if (cd1 != null && cd2 == null)
                        {
                            listtypes.add(tp);
                            for (String ext : tp.getFileSpecs(IContentType.FILE_EXTENSION_SPEC))
                            {
                                // Does extension itself contains a dot, and
                                // matches this file ?
                                if (ext.indexOf('.') >= 0 && name.endsWith("." + ext)) //$NON-NLS-1$
                                {
                                    // It has a dot, so remove
                                    n1 = name.substring(0, name.length() - ext.length());
                                    // This looks a good content type as matches
                                    // with long extension.
                                    // So put it first.
                                    listtypes.remove(tp);
                                    listtypes.add(0, tp);
                                }
                            }
                        }
                    }
                }
                name = n1;
            }

            int p = name.lastIndexOf('.');
            name = p >= 0 ? name.substring(0, p + 1) : name + ".";//$NON-NLS-1$
            String prefix = new File(file.getParentFile(), name).getAbsolutePath();
            String snapshot_identifier = args.get("snapshot_identifier"); //$NON-NLS-1$
            if (snapshot_identifier != null)
            {
                prefix += snapshot_identifier + "."; //$NON-NLS-1$
            }

            wrappedListener.setFile(new File(prefix + "log.index"));

            try
            {
                File indexFile = new File(prefix + "index");//$NON-NLS-1$
                if (indexFile.exists())
                {
                    // check if hprof file is newer than index file
                    if (file.lastModified() <= indexFile.lastModified())
                    {
                        answer = SnapshotImpl.readFromFile(file, prefix, wrappedListener);
                    }
                    else
                    {
                        String message = MessageUtil.format(
                                        Messages.SnapshotFactoryImpl_ReparsingHeapDumpAsIndexOutOfDate, file.getPath(),
                                        new Date(file.lastModified()), indexFile.getPath(),
                                        new Date(indexFile.lastModified()));
                        wrappedListener.sendUserMessage(Severity.INFO, message, null);
                        wrappedListener.subTask(Messages.SnapshotFactoryImpl_ReparsingHeapDumpWithOutOfDateIndex);
                    }
                }
            }
            catch (IOException ignore_and_reparse)
            {
                String text = ignore_and_reparse.getMessage() != null ? ignore_and_reparse.getMessage()
                                : ignore_and_reparse.getClass().getName();
                String message = MessageUtil.format(Messages.SnapshotFactoryImpl_Error_ReparsingHeapDump, text);
                wrappedListener.sendUserMessage(Severity.WARNING, message, ignore_and_reparse);
                wrappedListener.subTask(message);
            }

            if (answer == null)
            {
                File lockFile = new File(prefix + "lock.index"); //$NON-NLS-1$
                /*
                 * For autocloseable, the closeable object will be closed when
                 * parsing is done. This will release the lock and delete the
                 * lock file.
                 */
                try (Closeable ac = lockParse(file, lockFile, wrappedListener))
                {
                    deleteIndexFiles(file, prefix, lockFile, wrappedListener);
                    answer = parse(file, prefix, args, listtypes, wrappedListener);
                }
                catch (IOException e)
                {
                    throw new SnapshotException(e);
                }
            }

            entry = new SnapshotEntry(1, answer);

            snapshotCache.put(file, entry);

            return answer;
        }
        finally
        {
            ZonedDateTime end = ZonedDateTime.now();
            Duration duration = Duration.between(start, end);
            // ISO8601 format, e.g. PT10H30M182S
            String humanDuration = duration.toString().substring(2) /*
                                                                     * remove
                                                                     * leading
                                                                     * PT
                                                                     */
                            .replaceAll("([HMS])", "$1 ").toLowerCase().trim();
            double durationSeconds = (double) duration.toMillis() / 1000D;
            wrappedListener.sendUserMessage(Severity.INFO,
                            MessageUtil.format(Messages.SnapshotFactoryImpl_FinishOpeningDump,
                                            new DecimalFormat("#,###.00").format(durationSeconds), humanDuration,
                                            DateTimeFormatter.ISO_ZONED_DATE_TIME.format(end)),
                            null);
        }
    }

    /**
     * Create a lock to stop concurrent parsing.
     * @param file The dump - used for a message in the lock file
     * @param lockFile The lock file
     * @return an object which will be auto closed when the parsing is done.
     * @throws SnapshotException
     */
    private Closeable lockParse(File file, File lockFile, IProgressListener listener) throws SnapshotException
    {
        FileLock l1;
        try
        {
            FileChannel fc = FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            int msgSize = 1024;
            ByteBuffer buf = ByteBuffer.allocate(msgSize);
            Exception e1 = null;
            FileLock l;
            try
            {
                // Get the lock
                l = fc.tryLock(msgSize, Long.MAX_VALUE - msgSize, false);
            }
            catch (OverlappingFileLockException e)
            {
                e1 = e;
                l = null;
            }
            if (l != null)
            {
                l1 = l;
                // Write a lock message
                Date date = new Date();
                String message = MessageUtil.format(Messages.SnapshotFactoryImpl_MATParsingLock, file, System.getProperty("user.name"), date); //$NON-NLS-1$
                buf.put(message.getBytes(StandardCharsets.UTF_8));
                buf.flip();
                fc.write(buf);
                fc.force(true);
            }
            else
            {
                // find out who has the lock
                fc.read(buf);
                buf.flip();
                int lim = buf.limit();
                byte b[] = new byte[lim];
                buf.get(b);
                String message = new String(b, StandardCharsets.UTF_8);
                throw new SnapshotException(MessageUtil.format(Messages.SnapshotFactoryImpl_ConcurrentParsingError, lockFile, message), e1);
            }
        }
        catch (IOException e)
        {
            throw new SnapshotException(e);
        }
        return new Closeable()
        {

            @Override
            public void close()
            {
                IOException e1 = null;
                try
                {
                    l1.release();
                }
                catch (IOException e)
                {
                    e1 = e;
                }
                try
                {
                    l1.acquiredBy().close();
                }
                catch (IOException e)
                {
                    e1 = e;
                }
                boolean deleted = lockFile.delete();
                if (!deleted)
                {
                    listener.sendUserMessage(Severity.WARNING, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_UnableToDeleteIndexFile, lockFile.toString()), e1);
                }
            }
        };
    }

    public synchronized void dispose(ISnapshot snapshot)
    {

        for (Iterator<SnapshotEntry> iter = snapshotCache.values().iterator(); iter.hasNext();)
        {
            SnapshotEntry entry = iter.next();

            ISnapshot s = entry.snapshot.get();
            if (s == null)
            {
                iter.remove();
            }
            else if (s == snapshot)
            {
                entry.usageCount--;
                if (entry.usageCount == 0)
                {
                    snapshot.dispose();
                    iter.remove();
                }
                return;
            }
        }

        // just in case the snapshot is not stored anymore
        if (snapshot != null)
            snapshot.dispose();
    }

    public IOQLQuery createQuery(String queryString) throws SnapshotException
    {
        return new OQLQueryImpl(queryString);
    }

    public List<SnapshotFormat> getSupportedFormats()
    {
        List<SnapshotFormat> answer = new ArrayList<SnapshotFormat>();

        for (Parser parser : ParserPlugin.getDefault().getParserRegistry().delegates())
            answer.add(parser.getSnapshotFormat());

        return answer;
    }

    // //////////////////////////////////////////////////////////////
    // Internal implementations
    // //////////////////////////////////////////////////////////////

    private void addAllNew(List<ParserRegistry.Parser> list, List<ParserRegistry.Parser> add)
    {
        for (ParserRegistry.Parser p2 : add)
        {
            boolean found = false;
            for (ParserRegistry.Parser p3 : list)
            {
                if (p3.getUniqueIdentifier().equals(p2.getUniqueIdentifier()))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
                list.add(p2);
        }
    }

    private final ISnapshot parse(File file, String prefix, Map<String, String> args, List<IContentType>listtypes, IProgressListener listener) throws SnapshotException
    {
        ParserRegistry registry = ParserPlugin.getDefault().getParserRegistry();

        List<ParserRegistry.Parser> parsersfn = registry.matchParser(file.getName());
        List<ParserRegistry.Parser> parsers = new ArrayList<ParserRegistry.Parser>();
        if (parsersfn.isEmpty())
            parsers.addAll(registry.delegates()); // try all...
        else
        {
            // Add some extra parsers by content type
            // Presume content types in preference order, so add first
            for (IContentType type : listtypes)
            {
                // Parsers don't match for equality
                List<ParserRegistry.Parser> parsersct = registry.matchParser(type);
                addAllNew(parsers, parsersct);
            }
            addAllNew(parsers, parsersfn);
        }

        List<IOException> errors = new ArrayList<IOException>();

        for (Parser parser : parsers)
        {
            IIndexBuilder indexBuilder = parser.create(IIndexBuilder.class, ParserRegistry.INDEX_BUILDER);

            if (indexBuilder == null)
                continue;

            try
            {
                indexBuilder.init(file, prefix);

                XSnapshotInfo snapshotInfo = new XSnapshotInfo();
                snapshotInfo.setPath(file.getAbsolutePath());
                snapshotInfo.setPrefix(prefix);
                snapshotInfo.setProperty("$heapFormat", parser.getId());//$NON-NLS-1$
                if (Boolean.parseBoolean(args.get("keep_unreachable_objects")))//$NON-NLS-1$
                {
                    snapshotInfo.setProperty("keep_unreachable_objects", GCRootInfo.Type.UNREACHABLE);//$NON-NLS-1$
                }
                if (args.containsKey("discard_ratio")) //$NON-NLS-1$
                {
                    snapshotInfo.setProperty("discard_ratio", Integer.parseInt(args.get("discard_ratio")));  //$NON-NLS-1$//$NON-NLS-2$
                    if (args.containsKey("discard_pattern")) //$NON-NLS-1$
                        snapshotInfo.setProperty("discard_pattern", args.get("discard_pattern")); //$NON-NLS-1$ //$NON-NLS-2$
                    if (args.containsKey("discard_offset")) //$NON-NLS-1$
                        snapshotInfo.setProperty("discard_offset", Integer.parseInt(args.get("discard_offset"))); //$NON-NLS-1$ //$NON-NLS-2$
                    if (args.containsKey("discard_seed")) //$NON-NLS-1$
                        snapshotInfo.setProperty("discard_seed", Integer.parseInt(args.get("discard_seed"))); //$NON-NLS-1$ //$NON-NLS-2$
                }

                String snapshot_identifier = args.get("snapshot_identifier"); //$NON-NLS-1$
                if (snapshot_identifier != null)
                {
                    snapshotInfo.setProperty("$runtimeId", snapshot_identifier);//$NON-NLS-1$
                }

                PreliminaryIndexImpl idx = new PreliminaryIndexImpl(snapshotInfo);
                SimpleMonitor monitor = new SimpleMonitor(MessageUtil
                                .format(Messages.SnapshotFactoryImpl_ParsingHeapDump, file.getAbsolutePath()), listener,
                                new int[] { 700, 30, 90, 20, 150, 10 });

                indexBuilder.fill(idx, monitor.nextMonitor());

                IProgressListener mon = monitor.nextMonitor();
                if (ParserPlugin.getDefault().isDebugging())
                {
                    validateIndices(idx, mon);
                }

                SnapshotImplBuilder builder = new SnapshotImplBuilder(idx.getSnapshotInfo());

                int[] purgedMapping = GarbageCleaner.clean(idx, builder, args, monitor.nextMonitor());

                indexBuilder.clean(purgedMapping, monitor.nextMonitor());

                purgedMapping = null;

                SnapshotImpl snapshot = builder.create(parser, listener);
                boolean done = false;
                try
                {
                    snapshot.calculateDominatorTree(monitor.nextMonitor());
                    snapshot.calculateMinRetainedHeapSizeForClasses(monitor.nextMonitor());
                    done = true;
                }
                finally
                {
                    if (!done)
                    {
                        // Error in dominator tree, so close the index files
                        snapshot.dispose();
                    }
                }

                listener.done();
                return snapshot;
            }
            catch (IOException ioe)
            {
                errors.add(ioe);
                indexBuilder.cancel();
                /*
                 * We shouldn'r really attempt to reuse the listener, but this is
                 * the best we can do.
                 */
                listener.done();
            }
            catch (Exception e)
            {
                indexBuilder.cancel();

                throw SnapshotException.rethrow(e);
            }
        }

        if (errors.size() > 1)
        {
            MultiStatus status = new MultiStatus(ParserPlugin.PLUGIN_ID, 0,
                            MessageUtil.format(Messages.SnapshotFactoryImpl_ErrorOpeningHeapDump, file.getName()), null);
            for (IOException error : errors)
                status.add(new Status(IStatus.ERROR, ParserPlugin.PLUGIN_ID, 0, error.getLocalizedMessage(), error));
            // Create a CoreException so that all the errors will be logged
            CoreException ce = new CoreException(status);

            throw new SnapshotException(MessageUtil.format(Messages.SnapshotFactoryImpl_Error_OpeningHeapDump, file
                            .getName()), ce);
        }
        else if (errors.size() == 1)
        {
            throw new SnapshotException(MessageUtil.format(Messages.SnapshotFactoryImpl_Error_OpeningHeapDump, file
                            .getName()), errors.get(0));
        }
        else
        {
            throw new SnapshotException(MessageUtil.format(Messages.SnapshotFactoryImpl_Error_NoParserRegistered, file
                            .getName()));
        }
    }

    /**
     * Check that indices look valid
     *
     * @param listener
     */
    private void validateIndices(PreliminaryIndexImpl pidx, IProgressListener listener)
    {
        final int maxIndex = pidx.identifiers.size();
        listener.beginTask(Messages.SnapshotFactoryImpl_ValidatingIndices, maxIndex / 1000 + 1);
        long prevAddress = -1;
        int nObjs = 0;
        int nObjsFromClass = 0;
        int nCls = 0;
        // Look at each object
        for (int i = 0; i < maxIndex; ++i)
        {
            if (i % 1000 == 0)
            {
                if (listener.isCanceled()) { throw new IProgressListener.OperationCanceledException(); }
                listener.worked(1);
            }
            // Check addresses are in ascending order
            long addr = pidx.identifiers.get(i);
            if (prevAddress == addr)
            {
                String desc = objDesc(pidx, i);
                int j = pidx.identifiers.reverse(addr);
                String desc2 = objDesc(pidx, j);
                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                Messages.SnapshotFactoryImpl_IndexAddressHasSameAddressAsPrevious, i, desc, format(addr),
                                desc2), null);
            }
            if (prevAddress > addr)
            {
                String desc = objDesc(pidx, i);
                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                Messages.SnapshotFactoryImpl_IndexAddressIsSmallerThanPrevious, i, desc, format(addr),
                                format(prevAddress)), null);
            }
            prevAddress = addr;
            int j = pidx.identifiers.reverse(addr);
            if (i != j)
            {
                String desc1 = objDesc(pidx, i);
                String desc2 = objDesc(pidx, j);
                listener.sendUserMessage(Severity.ERROR,
                                MessageUtil.format(Messages.SnapshotFactoryImpl_IndexAddressFoundAtOtherID, i,
                                                format(addr), j, desc1, desc2), null);
            }
            // Check the type of each object
            int clsId = pidx.object2classId.get(i);
            if (clsId < 0)
            {
                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                Messages.SnapshotFactoryImpl_ClassIDNotFound, i, format(addr), clsId), null);
            }
            else
            {
                ClassImpl ci = pidx.classesById.get(clsId);
                if (ci == null)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_ClassImplNotFound, i, format(addr), clsId), null);
                }
            }
            // Check the outbounds of each object
            int outs[] = pidx.outbound.get(i);
            if (outs == null)
            {
                String desc = objDesc(pidx, i);
                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                Messages.SnapshotFactoryImpl_NoOutbounds, i, format(addr), desc), null);
            }
            else
            {
                if (outs.length == 0)
                {
                    String desc = objDesc(pidx, i);
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_EmptyOutbounds, i, format(addr), desc), null);
                }
                else
                {
                    for (int k = 0; k < outs.length; ++k)
                    {
                        if (outs[k] < 0 || outs[k] >= maxIndex)
                        {
                            String desc = objDesc(pidx, i);
                            listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                            Messages.SnapshotFactoryImpl_InvalidOutbound, i, format(addr), desc, k, outs[k]), null);

                        }
                    }
                    if (outs[0] != clsId) {
                        long address = outs[0] >= 0 && outs[0] < maxIndex ? pidx.identifiers.get(outs[0]) : -1;
                        String desc = objDesc(pidx, i);
                        long clsAddress = clsId >= 0 && outs[0] < maxIndex ? pidx.identifiers.get(clsId) : -1;
                        listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_InvalidFirstOutbound, i, format(addr), desc, outs[0], format(address), clsId, format(clsAddress)), null);
                    }
                }
            }
            // Check the object itself, and do special checks for plain objects or class objects
            ClassImpl ci = pidx.classesById.get(i);
            if (ci == null)
            {
                ++nObjs;
                // Ordinary object
                long size = pidx.array2size.getSize(i);
                if (size < 0)
                {
                    ci = pidx.classesById.get(clsId);
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_IndexAddressNegativeArraySize, i, format(addr), size, ci
                                                    .getTechnicalName()), null);
                }
            }
            else
            {
                ++nCls;
                long addr2 = ci.getObjectAddress();
                if (addr != addr2)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_ClassIndexAddressNotEqualClassObjectAddress, i,
                                    format(addr), format(addr2), ci.getTechnicalName()), null);
                }
                int id = ci.getObjectId();
                if (i != id)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_ClassIndexNotEqualClassObjectID, i, format(addr), id, ci
                                                    .getTechnicalName()), null);
                }
                int clsId2 = ci.getClassId();
                if (clsId != clsId2)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_ClassIndexAddressTypeIDNotEqualClassImplClassId, i,
                                    format(addr), clsId, clsId2, ci.getTechnicalName()), null);
                }
                long ldrAddr = ci.getClassLoaderAddress();
                int ldr = ci.getClassLoaderId();
                if (ldr < 0)
                {
                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                    Messages.SnapshotFactoryImpl_ClassIndexAddressNoLoaderID, i, format(addr), clsId, ldr,
                                    format(ldrAddr), ci.getTechnicalName()), null);
                }
                nObjsFromClass += ci.getNumberOfObjects();
            }
        }
        if (nObjsFromClass != nObjs + nCls)
        {
            listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                            Messages.SnapshotFactoryImpl_ObjectsFoundButClassesHadObjectsAndClassesInTotal, nObjs, nCls,
                            nObjsFromClass), null);
        }
        listener.subTask(Messages.SnapshotFactoryImpl_ValidatingGCRoots);
        // Check some GC information
        for (IteratorInt it = pidx.gcRoots.keys(); it.hasNext();)
        {
            int idx = it.next();
            if (idx < 0 || idx >= maxIndex)
            {
                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                Messages.SnapshotFactoryImpl_GCRootIDOutOfRange, idx, maxIndex), null);
            }
            else
            {
                for (ListIterator<XGCRootInfo> it2 = pidx.gcRoots.get(idx).listIterator(); it2.hasNext();)
                {
                    XGCRootInfo ifo = it2.next();
                    int objid = ifo.getObjectId();
                    if (objid != idx)
                    {
                        listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                        Messages.SnapshotFactoryImpl_GCRootIDDoesNotMatchIndex, objid, idx), null);
                    }
                    long objaddr = ifo.getObjectAddress();
                    int j = pidx.identifiers.reverse(objaddr);
                    if (j != idx)
                    {
                        listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                        Messages.SnapshotFactoryImpl_GCRootIDDoesNotMatchAddress, objid, Long.toHexString(objaddr)), null);
                    }
                    int ctxidx = ifo.getContextId();
                    long ctxaddr = ifo.getContextAddress();
                    if (ctxaddr != 0)
                    {
                        if (ctxidx < 0 || ctxidx >= maxIndex)
                        {
                            listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                            Messages.SnapshotFactoryImpl_GCRootIDOutOfRange, ctxidx, maxIndex), null);
                        }
                        int k = pidx.identifiers.reverse(ctxaddr);
                        if (k != ctxidx)
                        {
                            listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                            Messages.SnapshotFactoryImpl_GCRootContextIDDoesNotMatchAddress, objid, ctxidx, Long.toHexString(ctxaddr)), null);
                        }
                    }
                }
            }
        }
        for (int thrd : pidx.thread2objects2roots.getAllKeys())
        {
            if (thrd < 0 || thrd >= maxIndex)
            {
                listener.sendUserMessage(Severity.ERROR,
                                MessageUtil.format(Messages.SnapshotFactoryImpl_GCThreadIDOutOfRange, thrd, maxIndex), null);
            }
            else
            {
                HashMapIntObject<List<XGCRootInfo>> l = pidx.thread2objects2roots.get(thrd);
                for (int idx : l.getAllKeys())
                {
                    if (idx < 0 || idx >= maxIndex)
                    {
                        listener.sendUserMessage(Severity.ERROR, MessageUtil
                                        .format(Messages.SnapshotFactoryImpl_GCThreadRootIDOutOfRange, thrd, idx,
                                                        maxIndex), null);
                    }
                    else
                    {
                        for (XGCRootInfo ifo : l.get(idx))
                        {
                            int objid = ifo.getObjectId();
                            if (objid != idx)
                            {
                                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                                Messages.SnapshotFactoryImpl_GCThreadRootIDDoesNotMatchIndex,
                                                thrd, objid, idx), null);
                            }
                            long objaddr = ifo.getObjectAddress();
                            int j = pidx.identifiers.reverse(objaddr);
                            if (j != idx)
                            {
                                listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                                Messages.SnapshotFactoryImpl_GCRootIDDoesNotMatchAddress, objid, Long.toHexString(objaddr)), null);
                            }
                            int ctxidx = ifo.getContextId();
                            long ctxaddr = ifo.getContextAddress();
                            if (ctxaddr != 0)
                            {
                                if (ctxidx < 0 || ctxidx >= maxIndex)
                                {
                                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                                    Messages.SnapshotFactoryImpl_GCRootIDOutOfRange, ctxidx, maxIndex), null);
                                }
                                int k = pidx.identifiers.reverse(ctxaddr);
                                if (k != ctxidx)
                                {
                                    listener.sendUserMessage(Severity.ERROR, MessageUtil.format(
                                                    Messages.SnapshotFactoryImpl_GCRootContextIDDoesNotMatchAddress, objid, ctxidx, Long.toHexString(ctxaddr)), null);
                                }
                            }
                        }
                    }
                }
            }
        }
        listener.done();
    }

    /**
     * Convert an address to a 0x hex number
     *
     * @param address
     * @return A string representing the address
     */
    private static String format(long address)
    {
        return "0x" + Long.toHexString(address); //$NON-NLS-1$
    }

    /**
     * Describe the class of the object at the given index
     *
     * @param newObjId
     * @return a class description
     */
    private String objDesc(PreliminaryIndexImpl pidx, int newObjId)
    {
        String clsInfo;
        if (newObjId >= 0)
        {
            ClassImpl classInfo = pidx.classesById.get(newObjId);
            if (classInfo != null)
            {
                clsInfo = MessageUtil.format(Messages.SnapshotFactoryImpl_ObjDescClass, classInfo.getName());
            }
            else
            {
                int clsId = pidx.object2classId.get(newObjId);
                if (clsId >= 0 && clsId < pidx.identifiers.size())
                {
                    long clsAddr = pidx.identifiers.get(clsId);
                    classInfo = pidx.classesById.get(clsId);
                    // If objectToClass has not yet been filled in for objects
                    // then this could be null
                    if (classInfo != null)
                    {
                        clsInfo = MessageUtil.format(Messages.SnapshotFactoryImpl_ObjDescObjType, classInfo.getName(),
                                        format(clsAddr));
                    }
                    else
                    {
                        clsInfo = MessageUtil
                                        .format(Messages.SnapshotFactoryImpl_ObjDescObjTypeAddress, format(clsAddr));
                    }
                }
                else
                {
                    clsInfo = ""; //$NON-NLS-1$
                }
            }
        }
        else
        {
            clsInfo = ""; //$NON-NLS-1$
        }
        return clsInfo;
    }

    private void deleteIndexFiles(File file, final String prefix, File lockFile, IProgressListener listener)
    {
        File prefixFile = new File(prefix);
        File directory = prefixFile.getParentFile();
        if (directory == null)
            directory = new File("."); //$NON-NLS-1$

        final String fragment = prefixFile.getName();

        final Pattern indexPattern = Pattern.compile("([A-Za-z0-9]{1,20}\\.)?index$"); //$NON-NLS-1$
        final Pattern threadPattern = Pattern.compile("threads$"); //$NON-NLS-1$
        final Pattern logPattern = Pattern.compile("inbound\\.index\\.([0-9]+\\.){1,2}log$"); //$NON-NLS-1$

        File[] files = directory.listFiles(new FileFilter()
        {
            public boolean accept(File f)
            {
                if (f.isDirectory())
                    return false;

                String name = f.getName();
                return name.startsWith(fragment)
                                && !name.equals(lockFile.getName())
                                && (indexPattern.matcher(name.substring(fragment.length())).matches()
                                  || threadPattern.matcher(name.substring(fragment.length())).matches()
                                  || logPattern.matcher(name.substring(fragment.length())).matches());
            }
        });

        if (files != null)
        {
            for (File f : files)
            {
                boolean deleted = f.delete();
                if (!deleted)
                {
                    listener.sendUserMessage(Severity.WARNING,
                                    MessageUtil.format(Messages.SnapshotFactoryImpl_UnableToDeleteIndexFile, f.toString()), null);
                }
            }
        }
    }
}
