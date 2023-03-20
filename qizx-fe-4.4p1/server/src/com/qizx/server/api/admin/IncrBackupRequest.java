/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.api.admin;

import com.qizx.server.util.RequestException;

import java.io.IOException;

/**
 * Start backup of a Library. Long operation returning a Progress Identifier.
 */
public class IncrBackupRequest extends BackupRequest
{
    public String getName()
    {
        return "incrbackup";
    }

    public void handlePost()
        throws RequestException, IOException
    {
        handleBackup(true);
    }
}
