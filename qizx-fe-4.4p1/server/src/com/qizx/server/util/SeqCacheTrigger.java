/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.util;

import com.qizx.api.DataModelException;
import com.qizx.api.Library;
import com.qizx.api.LibraryMemberIterator;
import com.qizx.api.PostCommitTrigger;
import com.qizx.api.User;

/**
 * Performs "intelligent" cache invalidation
 */
public class SeqCacheTrigger
    implements PostCommitTrigger
{
    private SequenceCache cache;

    public SeqCacheTrigger(SequenceCache cache)
    {
        this.cache = cache;
    }

    public void commit(CommitEvent event)
    {
        if (false) 
          try {
            
            System.err.println(" created docs "+event.createdDocumentCount());
            LibraryMemberIterator iter = event.createdDocuments();
            for(; iter.moveToNextMember(); ) {
                
            }

            System.err.println(" deleted docs "+event.deletedDocumentCount());
            iter = event.deletedDocuments();
            for(; iter.moveToNextMember(); ) {
                System.err.println("  "+iter.getCurrentMember());
            }

            System.err.println(" affected collecs "+event.updatedCollectionCount());
            iter = event.updatedCollections();
            for(; iter.moveToNextMember(); ) {
                System.err.println("  "+iter.getCurrentMember());
            }
          }
          catch (DataModelException e1) {
            // TODO 
            e1.printStackTrace();
          }

        int ccount = event.updatedCollectionCount();
        if(ccount == 0)
            return;     // impossible?
        String[] collecs = new String[ccount];
        try {
            LibraryMemberIterator iter = event.updatedCollections();
            for(int c = 0; iter.moveToNextMember(); )
                collecs[c++] = iter.getCurrentMember().getPath();
            
            Library lib = event.getLibrary();
            User u = lib.getUser();
            if(cache.isSmart())
                cache.invalidate(lib.getName(), 
                                 (u == null)? null : u.getName(), collecs);
            else
                cache.invalidateAll(lib.getName());
        }
        catch (DataModelException e) {
            e.printStackTrace();
        }
    }
}
