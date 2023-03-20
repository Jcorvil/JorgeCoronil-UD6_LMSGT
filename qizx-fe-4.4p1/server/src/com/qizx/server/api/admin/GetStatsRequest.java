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
import com.qizx.api.util.logging.Statistic;
import com.qizx.api.util.logging.Statistics;
import com.qizx.apps.restapi.RestAPI;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.util.TableGenerator;
import com.qizx.xquery.ext.AdminFunctions;

import java.io.IOException;

/**
 * Returns a list of statistics from the 
 * <p>Parameters:
 * <li>xid: identifier of a running query
 * <p>Returns: 
 * 
 */
public class GetStatsRequest extends QizxRequestBase
{
    static final String ADMIN_LEVEL = "admin";
    static final String EXPERT_LEVEL = "expert";
    

    public String getName()
    {
        return "getstats";
    }

    public void handleGet()
        throws RequestException, IOException
    {
        checkAdminRole(driver);
        try {
            LibraryManager engine = requireEngine();
            String format = getParameter("format", "text");
            String level = getParameter("level", "admin");
            
            
            Statistic.Map stats = new Statistic.Map();
            if (EXPERT_LEVEL.equalsIgnoreCase(level))
                stats.setMapping(Statistics.SHORT_MAPPING);
            else if (!"full".equalsIgnoreCase(level))
                stats.setMapping(Statistics.ADMIN_MAPPING);
            
            engine.collectStatistics(stats);
            driver.collectStatistics(stats);
            
            TableGenerator tg = prepareTableFormat(format, RestAPI.STATS_FIELDS);
            tg.startTable();
            AdminFunctions.generateStats(stats, tg);
            print(tg.endTable().toString());
        }
        catch (Exception e) {
            throw new RequestException(e);
        }
    }
}
