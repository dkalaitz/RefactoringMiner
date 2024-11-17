package com.netflix.exhibitor.core.config;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * An approximation of a lock using S3
 */
public abstract class PseudoLockBase implements PseudoLock
{
    private final String                    lockPrefix;
    private final int                       timeoutMs;
    private final String                    id;
    private final int                       pollingMs;
    private final int                       settlingMs;

    // all guarded by sync
    private long      lastUpdateMs = 0;
    private boolean   ownsTheLock;
    private String    key;

    private static final Random             random = new SecureRandom();

    private static final String     SEPARATOR = "_";
    private static final int        DEFAULT_SETTLING_MS = 5000;

    /**
     * @param lockPrefix key prefix
     * @param timeoutMs max age for locks
     * @param pollingMs how often to poll S3
     */
    public PseudoLockBase(String lockPrefix, int timeoutMs, int pollingMs)
    {
        this(lockPrefix, timeoutMs, pollingMs, DEFAULT_SETTLING_MS);
    }

    /**
     * @param lockPrefix key prefix
     * @param timeoutMs max age for locks
     * @param pollingMs how often to poll S3
     * @param settlingMs how long to wait for S3 to reach consistency
     */
    public PseudoLockBase(String lockPrefix, int timeoutMs, int pollingMs, int settlingMs)
    {
        this.settlingMs = settlingMs;
        Preconditions.checkArgument(!lockPrefix.contains(SEPARATOR), "lockPrefix cannot contain " + SEPARATOR);

        this.pollingMs = pollingMs;
        this.lockPrefix = lockPrefix;
        this.timeoutMs = timeoutMs;

        id = UUID.randomUUID().toString() + SEPARATOR + System.currentTimeMillis();
    }

    /**
     * Acquire the lock, blocking until it is acquired
     *
     * @throws Exception errors
     */
    public synchronized void  lock() throws Exception
    {
        lock(0, null);
    }

    /**
     * Acquire the lock, blocking at most <code>maxWait</code> until it is acquired
     *
     * @param maxWait max time to wait
     * @param unit time unit
     * @return true if the lock was acquired
     * @throws Exception errors
     */
    public synchronized boolean lock(long maxWait, TimeUnit unit) throws Exception
    {
        if ( ownsTheLock )
        {
            throw new IllegalStateException("Already locked");
        }

        key = lockPrefix + SEPARATOR + newRandomSequence();

        long        startMs = System.currentTimeMillis();
        boolean     hasMaxWait = (unit != null);
        long        maxWaitMs = hasMaxWait ? TimeUnit.MILLISECONDS.convert(maxWait, unit) : Long.MAX_VALUE;
        Preconditions.checkState(maxWaitMs >= settlingMs, String.format("The maxWait ms (%d) is less than the settling ms (%d)", maxWaitMs, settlingMs));

        createFile(key, id.getBytes());

        for(;;)
        {
            checkUpdate();
            if ( ownsTheLock )
            {
                break;
            }
            long        thisWaitMs;
            if ( hasMaxWait )
            {
                long        elapsedMs = System.currentTimeMillis() - startMs;
                thisWaitMs = maxWaitMs - elapsedMs;
                if ( thisWaitMs <= 0 )
                {
                    break;
                }
            }
            else
            {
                thisWaitMs = pollingMs;
            }
            wait(Math.min(pollingMs, thisWaitMs));
        }
        return ownsTheLock;
    }

    /**
     * Release the lock
     *
     * @throws Exception errors
     */
    public synchronized void unlock() throws Exception
    {
        if ( !ownsTheLock )
        {
            throw new IllegalStateException("Already unlocked");
        }

        deleteFile(key);
        notifyAll();
    }

    protected abstract void createFile(String key, byte[] contents) throws Exception;

    protected abstract void deleteFile(String key) throws Exception;

    protected abstract byte[] getFileContents(String key) throws Exception;

    protected abstract List<String> getFileNames(String lockPrefix) throws Exception;

    private void checkUpdate() throws Exception
    {
        if ( (System.currentTimeMillis() - lastUpdateMs) < pollingMs )
        {
            return;
        }

        List<String>        keys = getFileNames(lockPrefix);
        keys = cleanOldObjects(keys);
        Collections.sort(keys);

        if ( keys.size() > 0 )
        {
            String      lockerKey = keys.get(0);
            byte[]      bytes = getFileContents(lockerKey);
            if ( bytes != null )
            {
                String      lockerId = new String(bytes);
                long        lockerAge = System.nanoTime() - getNanoStampForKey(key);
                ownsTheLock = (lockerKey.equals(key) && lockerId.equals(id)) && (lockerAge >= TimeUnit.NANOSECONDS.convert(settlingMs, TimeUnit.MILLISECONDS));
            }
            else    // was deleted probably
            {
                ownsTheLock = false;
            }
        }
        else
        {
            throw new Exception("Our key is missing: " + key);
        }

        lastUpdateMs = System.currentTimeMillis();

        notifyAll();
    }

    private List<String>    cleanOldObjects(List<String> keys) throws Exception
    {
        List<String>        newKeys = Lists.newArrayList();
        for ( String key : keys )
        {
            long    nanoStamp = getNanoStampForKey(key);
            if ( (System.nanoTime() - nanoStamp) > TimeUnit.NANOSECONDS.convert(timeoutMs, TimeUnit.MILLISECONDS) )
            {
                deleteFile(key);
            }
            else
            {
                newKeys.add(key);
            }
        }
        return newKeys;
    }

    private static long getNanoStampForKey(String key)
    {
        String[]        parts = key.split(SEPARATOR);
        long            millisecondStamp = 0;
        try
        {
            millisecondStamp = Long.parseLong(parts[1]);
        }
        catch ( NumberFormatException ignore )
        {
            // ignore
        }
        return millisecondStamp;
    }

    private String newRandomSequence()
    {
        return "" + System.nanoTime() + SEPARATOR + Math.abs(random.nextLong());
    }
}
