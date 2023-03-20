/*
 *    Qizx Free_Engine-4.4p1
 *
 *    This code is part of the Qizx application components
 *    Copyright (c) 2004-2010 Axyana Software -- All rights reserved.
 *
 *    For conditions of use, see the accompanying license files.
 */
package com.qizx.server.api.admin;

import com.qizx.api.LibraryManager;
import com.qizx.apps.restapi.RestAPI;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.util.TableGenerator;
import com.qizx.xquery.ext.AdminFunctions;

import java.io.IOException;

/**
 * Returns a list of currently executing XQuery expressions.
 * <p>Parameters:
 * <p>Returns: 
 * a list of text lines, one for each running query.
 * Each line contains tab-separated fields:
 * - identifer of the query, which can be used in request 'cancelquery'
 * - user name ('?' if unknown)
 * - current running time
 * - 40 first characters of the query source code (tab and LF replaced by space)
 */
public class ListQueriesRequest extends QizxRequestBase
{
    public String getName()
    {
        return "listqueries";
    }

    public void handleGet()
        throws RequestException, IOException
    {
        checkAdminRole(driver);
        try {
            LibraryManager engine = requireEngine();

            String format = getParameter("format", "text");

            TableGenerator tg = prepareTableFormat(format, RestAPI.RUNNING_QUERIES_FIELDS);
            tg.startTable();
            AdminFunctions.generateQueries(engine, tg);
            print(tg.endTable().toString());
        }
        catch (Exception e) {
            throw new RequestException(e);
        }
    }
}
