/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.util;

import com.qizx.api.Expression;
import com.qizx.api.ItemSequence;
import com.qizx.api.Library;
import com.qizx.api.User;
import com.qizx.api.util.logging.Statistics;
import com.qizx.util.basic.PathUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * A Pool of evaluated ItemSequences.
 * <p>
 * Everything is done so that a user who requires consecutive pages of items on a
 * given query would retrieve the same Sequence.
 */
public class SequenceCache
{
    private LinkedHashMap<String, Entry> hash;
    private int maxEntryCount;
    private int entryCount;
    //private int hits, misses;
    Statistics.Cache stats;
    private long maxSize;
    private boolean smart = true;
    
    public SequenceCache(int maxEntryCount, long maxSize)
    {
        this.maxEntryCount = maxEntryCount;
        this.maxSize = maxSize;
        hash = new LinkedHashMap<String, Entry>();
    }

    public boolean isSmart()
    {
        return smart ;
    }

    public void setSmart(boolean smart)
    {
        this.smart = smart;
    }

    public Statistics.Cache getStats()
    {
        return stats;
    }

    public void setStats(Statistics.Cache stats)
    {
        this.stats = stats;
    }

    /**
     * Looks for a ready computed sequence matching query and library, and closest
     * to startPos.
     * <p>A matching sequence is removed from the cache and returned.
     * @param mode 
     */
    public synchronized ItemSequence acquire(String libraryName, String user,
                                             String query, boolean profiling, long startPos)
    {
        
        if(stats != null)
            stats.count();
        long t0 = System.nanoTime();
        
        // must match these 3 keys:
        String key = hashKey(libraryName, user, query);
        Entry e = hash.get(key);
        if (e == null) { // try with null user (matches any user)
            key = hashKey(libraryName, null, query);
            e = hash.get(key);
        }

        if (e != null) {
            ItemSequence best = null;
            int bestPenalty = Integer.MAX_VALUE;
            for (ItemSequence s : e) {
                if (profiling && s.getExpression() != null &&
                                !s.getExpression().isProfiled())
                    continue;
                long dist = startPos - s.getPosition();
                int penalty;
                if (dist < 0)
                    // too far: higher penalty
                    penalty = (int) (-4 * dist);
                else
                    penalty = (int) dist;
                if (penalty < bestPenalty) {
                    bestPenalty = penalty;
                    best = s;
                }
            }
            if(best != null) {
                
                e.remove(best);
                -- entryCount;
                return best;
            }
        }
        
        if(stats != null)
            stats.addMiss(0, System.nanoTime() - t0);
        return null;
    }

    /**
     * Puts a sequence back to the cache. 
     * Note: a used Sequence is not in the cache.
     * This methods handles cache eviction in LRU way: older sequences are evicted
     * returns the evicted Sequence, or null if none.
     */
    public synchronized ItemSequence release(ItemSequence seq)
    {
        Expression expr = seq.getExpression();
        // FIX: if expression is closed it is useless to cache it
        if (expr == null || expr.isClosed())
            return null;
        // FIX: never cache sequences of expr that modify the context
        //   instead return the sequence itself: will release the session
        if (expr.isUpdating())
            return seq;
        
        String query = expr.getSource();
        Library library = expr.getLibrary();
        if (library == null)
            return null; // what's that crap?
        
        String libName = library.getName();
        User u = library.getUser();
        String userName = (u == null)? null : u.getName();
        

        String key = hashKey(libName, userName, query);
        Entry e = hash.get(key);
        if (e == null) {
            e = new Entry();
            hash.put(key, e);
        }
        e.add(seq);
        ++ entryCount;
        
        // eviction:
        if (entryCount > maxEntryCount) {
            // need to drop a sequence: 
            // we use a LinkedHashMap, so we get the oldest key:
            Iterator<String> iter = hash.keySet().iterator();
            if (iter.hasNext()) { // should
                key = iter.next();
                e = hash.get(key);
                if (e.size() > 0)
                    seq = e.remove(0);
                if (e.size() == 0)
                    hash.remove(key);
                return seq;
            }
        }
        return null;
    }
    
    /**
     * Invalidates any sequence evaluated on a particular XML Library.
     */
    public synchronized void invalidateAll(String libraryName)
    {
        for(Entry e : hash.values()) {
            for(int sp = e.size(); --sp >= 0; )
            {
                Expression expr = e.get(sp).getExpression();
                if(expr == null || expr.getLibrary() == null)
                    continue; // 
                Library library = expr.getLibrary();
                if (libraryName == null || 
                    libraryName.equals(library.getName())) {
                    e.remove(sp);
                }
            }
        }
    }
    
    /**
     * Invalidates any sequence whose Expression uses
     * - a Collection containing an updated path
     * - any Library member matching an updated path.
     */
    public synchronized void invalidate(String libraryName, String user,
                                        String[] updatedPaths)
    {
        boolean trace = false;
        if (trace) {
            System.err.println("invalidate "+updatedPaths.length);
            for(String path2 : updatedPaths)
                System.err.println("  = "+path2);
        }
        
        for(Entry e : hash.values()) {
            for(int sp = e.size(); --sp >= 0; )
            {
                Expression expr = e.get(sp).getExpression();
                if(expr == null || expr.getLibrary() == null)
                    continue; // 
                Library library = expr.getLibrary();
                if (!libraryName.equals(library.getName()))
                    continue;
                User u = library.getUser();
                String userName = (u == null)? null : u.getName();
                if (user != userName &&
                     !(user != null && userName != null && user.equals(userName)))
                    continue;
                
                if (expr.isUpdating()) { // Huh? should not happen
                    e.remove(sp);
                    if (trace) 
                        System.err.println("invalidate "+expr.getSource()+" b/c updating");
                    continue;
                }
                
                // matching entry:
                String[] usedPaths = expr.getRootPaths();
//                System.err.println("expr "+expr.getSource()+" deps "+usedPaths.length);
//                for(String p : usedPaths)
//                    System.err.println("  := "+p);
                
               mainLoop:
                for(String path1 : usedPaths) {
                    for(String path2 : updatedPaths) {
                        if (path1.equals(path2)
                             || PathUtil.contains(path1, false, path2)) {
                            e.remove(sp);
                            if (trace) 
                                System.err.println("invalidate "+expr.getSource()+" b/c of "+path2);
                            break mainLoop;
                        }
                    }
                }
            }
        }
    }

    private String hashKey(String libraryName, String user, String query)
    {
        return libraryName + "\u0001" + user + "\u0001" + query;
    }

    public class Entry extends ArrayList<ItemSequence>
    {
    }
}
