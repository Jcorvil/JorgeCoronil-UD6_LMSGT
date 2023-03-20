/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.api.admin;

import com.qizx.api.Expression;
import com.qizx.api.LibraryManager;
import com.qizx.api.QizxException;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.xquery.ext.AdminFunctions;

import java.io.IOException;

/**
 * Kills a running XQuery execution.
 * <p>Parameters:
 * <li>xid: identifier of a running query
 * <p>Returns: a line containing 'OK' if killed, or 'fail' if the running
 *   expression could not be found (possibly closed in between).
 */
public class CancelQueryRequest extends QizxRequestBase
{
    public String getName()
    {
        return "cancelquery";
    }

    public void handlePost()
        throws RequestException, IOException
    {
        String xid = getParameter("xid");
        if (xid == null)
            requiredParam("xid");
        
        try {
            LibraryManager engine = requireEngine();
            checkAdminRole(driver);

            response.setContentType(MIME_PLAIN_TEXT);

            Expression exp = AdminFunctions.findExpression(engine, xid);
            if (exp == null)
                println("unknown");
            else {
                boolean running = exp.getStartTime() > 0;
                exp.cancelEvaluation();
                println(running ? "OK" : "idle");
                if (running)
                    log("cancelled query " + xid);
            }
        }
        catch (QizxException e) {
            throw new RequestException(e);
        }
    }
}
