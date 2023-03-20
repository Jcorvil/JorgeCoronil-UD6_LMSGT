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
import com.qizx.api.admin.BackgroundTask;
import com.qizx.server.util.QizxRequestBase;
import com.qizx.server.util.RequestException;
import com.qizx.util.TableGenerator;
import com.qizx.xquery.ext.AdminFunctions;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * Returns a list of background tasks.
 * <p>Parameters:
 * <li>timeline: 0 for "current tasks", or if > 0 all tasks that started within
 * this number of hours in the past. For example timeline=24 returns all tasks
 * that started in the past 24 hours.
 * <p>Returns: 
 * a list of text lines, one for each task.
 * Each line contains tab-separated fields:
 * - nature of the task: Backup, Optimize, Reindex
 * - Library name 
 * - start time
 * - finish time (expected time if not finished)
 * - fraction done (0 to 1) 1 if finished
 */
public class ListTasksRequest extends QizxRequestBase
{
    static final String[] FIELDS = {
        "TaskName", "Library", "StartTime", "EndTime", "Duration", "Progress"
    };

    public String getName()
    {
        return "listtasks";
    }

    public void handleGet()
        throws RequestException, IOException
    {
        checkAdminRole(driver);
        try {
            LibraryManager engine = requireEngine();
            int timeline = getIntParameter("timeline", 0);
            String format = getParameter("format", "text");

            List<BackgroundTask> tasks = engine.listBackgroundTasks(timeline);
            
            TableGenerator tg = prepareTableFormat(format, FIELDS);
            tg.startTable();
            AdminFunctions.generateTasks(tasks, tg);
            print(tg.endTable().toString());
        }
        catch (Exception e) {
            throw new RequestException(e);
        }
    }
}
