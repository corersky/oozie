/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.oozie.command.coord;

import java.io.IOException;
import java.io.Reader;
import java.util.Date;
import org.apache.oozie.CoordinatorActionBean;
import org.apache.oozie.CoordinatorJobBean;
import org.apache.oozie.client.CoordinatorAction;
import org.apache.oozie.client.CoordinatorJob;
import org.apache.oozie.coord.CoordELFunctions;
import org.apache.oozie.dependency.FSURIHandler;
import org.apache.oozie.dependency.HCatURIHandler;
import org.apache.oozie.executor.jpa.CoordActionGetJPAExecutor;
import org.apache.oozie.executor.jpa.CoordActionInsertJPAExecutor;
import org.apache.oozie.executor.jpa.CoordJobInsertJPAExecutor;
import org.apache.oozie.executor.jpa.JPAExecutorException;
import org.apache.oozie.service.JPAService;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.URIHandlerService;
import org.apache.oozie.test.XDataTestCase;
import org.apache.oozie.util.DateUtils;
import org.apache.oozie.util.IOUtils;
import org.apache.oozie.util.PartitionWrapper;
import org.apache.oozie.util.XLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestCoordPushDependencyCheckXCommand extends XDataTestCase {
    private String TZ;
    private String server;
    private Services services = null;

    @Before
    protected void setUp() throws Exception {
        super.setUp();
        services = new Services();
        services.getConf().set(URIHandlerService.URI_HANDLERS,
                FSURIHandler.class.getName() + "," + HCatURIHandler.class.getName());
        services.init();
        server = getMetastoreAuthority();
        TZ = (getProcessingTZ().equals(DateUtils.OOZIE_PROCESSING_TIMEZONE_DEFAULT)) ? "Z" : getProcessingTZ()
                .substring(3);
    }

    @After
    protected void tearDown() throws Exception {
        Services.get().destroy();
        super.tearDown();
    }

    @Test
    public void testUpdateCoordTableSingleDep() throws Exception {
        // Test for single dependency which is already in the hcat server
        String db = "default";
        String table = "tablename";
        String newHCatDependency = "hcat://" + server + "/" + db + "/" + table + "/dt=20120430;country=usa";

        populateTable(db, table);

        String actionId = addInitRecords(newHCatDependency);
        checkCoordAction(actionId, newHCatDependency, CoordinatorAction.Status.WAITING, 0);

        new CoordPushDependencyCheckXCommand(actionId).call();

        checkCoordAction(actionId, "", CoordinatorAction.Status.READY, 0);

    }

    @Test
    public void testUpdateCoordTableMultipleDepsV1() throws Exception {
        // Test for two dependencies which are already in the hcat server
        String db = "default";
        String table = "tablename";
        String newHCatDependency1 = "hcat://" + server + "/" + db + "/" + table + "/dt=20120412;country=brazil";
        String newHCatDependency2 = "hcat://" + server + "/" + db + "/" + table + "/dt=20120430;country=usa";
        String newHCatDependency = newHCatDependency1 + CoordELFunctions.INSTANCE_SEPARATOR + newHCatDependency2;
        populateTable(db, table);

        String actionId = addInitRecords(newHCatDependency);
        checkCoordAction(actionId, newHCatDependency, CoordinatorAction.Status.WAITING, 0);

        new CoordPushDependencyCheckXCommand(actionId).call();

        checkCoordAction(actionId, "", CoordinatorAction.Status.READY, 0);

    }

    @Test
    public void testUpdateCoordTableMultipleDepsV2() throws Exception {
        // Test for two dependencies : one of them is already existed in the
        // hcat server. Other one is not.
        // Expected to see the action in WAITING
        // Later make the other partition also available. action is expected to
        // be READY
        String db = "default";
        String table = "tablename";
        String newHCatDependency1 = "hcat://" + server + "/" + db + "/" + table + "/dt=20120430;country=brazil";
        String newHCatDependency2 = "hcat://" + server + "/" + db + "/" + table + "/dt=20120430;country=usa";
        String newHCatDependency = newHCatDependency1 + CoordELFunctions.INSTANCE_SEPARATOR + newHCatDependency2;
        populateTable(db, table);

        String actionId = addInitRecords(newHCatDependency);
        checkCoordAction(actionId, newHCatDependency, CoordinatorAction.Status.WAITING, 0);

        new CoordPushDependencyCheckXCommand(actionId).call();

        checkCoordAction(actionId, newHCatDependency1, CoordinatorAction.Status.WAITING, 0);

        addPartition(db, table, "dt=20120430;country=brazil");

        new CoordPushDependencyCheckXCommand(actionId).call();

        checkCoordAction(actionId, "", CoordinatorAction.Status.READY, 0);
    }

    private void populateTable(String db, String table) throws Exception {
        dropTable(db, table, true);
        dropDatabase(db, true);
        createDatabase(db);
        createTable(db, table, "dt,country");
        addPartition(db, table, "dt=20120430;country=usa");
        addPartition(db, table, "dt=20120412;country=brazil");
        addPartition(db, table, "dt=20120413;country=brazil");
    }

    private CoordinatorActionBean checkCoordAction(String actionId, String expDeps, CoordinatorAction.Status stat,
            int type) throws Exception {
        try {
            JPAService jpaService = Services.get().get(JPAService.class);
            CoordinatorActionBean action = jpaService.execute(new CoordActionGetJPAExecutor(actionId));
            String missDeps = action.getPushMissingDependencies();
            if (type != 0) {
                assertEquals(new PartitionWrapper(missDeps), new PartitionWrapper(expDeps));
            }
            else {
                assertEquals(missDeps, expDeps);
            }
            assertEquals(action.getStatus(), stat);

            return action;
        }
        catch (JPAExecutorException se) {
            throw new Exception("Action ID " + actionId + " was not stored properly in db");
        }
    }

    private String addInitRecords(String pushMissingDependencies) throws Exception {
        Date startTime = DateUtils.parseDateOozieTZ("2009-02-01T23:59" + TZ);
        Date endTime = DateUtils.parseDateOozieTZ("2009-02-02T23:59" + TZ);
        CoordinatorJobBean job = addRecordToCoordJobTableForWaiting("coord-job-for-action-input-check.xml",
                CoordinatorJob.Status.RUNNING, startTime, endTime, false, true, 3);

        CoordinatorActionBean action1 = addRecordToCoordActionTableForWaiting(job.getId(), 1,
                CoordinatorAction.Status.WAITING, "coord-action-for-action-input-check.xml", pushMissingDependencies);
        return action1.getId();
    }

    protected CoordinatorActionBean addRecordToCoordActionTableForWaiting(String jobId, int actionNum,
            CoordinatorAction.Status status, String resourceXmlName, String pushMissingDependencies) throws Exception {
        CoordinatorActionBean action = createCoordAction(jobId, actionNum, status, resourceXmlName, 0, TZ);
        action.setPushMissingDependencies(pushMissingDependencies);
        try {
            JPAService jpaService = Services.get().get(JPAService.class);
            assertNotNull(jpaService);
            CoordActionInsertJPAExecutor coordActionInsertCmd = new CoordActionInsertJPAExecutor(action);
            jpaService.execute(coordActionInsertCmd);
        }
        catch (JPAExecutorException je) {
            je.printStackTrace();
            fail("Unable to insert the test coord action record to table");
            throw je;
        }
        return action;
    }

    protected CoordinatorJobBean addRecordToCoordJobTableForWaiting(String testFileName, CoordinatorJob.Status status,
            Date start, Date end, boolean pending, boolean doneMatd, int lastActionNum) throws Exception {

        String testDir = getTestCaseDir();
        CoordinatorJobBean coordJob = createCoordJob(testFileName, status, start, end, pending, doneMatd, lastActionNum);
        String appXml = getCoordJobXmlForWaiting(testFileName, testDir);
        coordJob.setJobXml(appXml);

        try {
            JPAService jpaService = Services.get().get(JPAService.class);
            assertNotNull(jpaService);
            CoordJobInsertJPAExecutor coordInsertCmd = new CoordJobInsertJPAExecutor(coordJob);
            jpaService.execute(coordInsertCmd);
        }
        catch (JPAExecutorException je) {
            je.printStackTrace();
            fail("Unable to insert the test coord job record to table");
            throw je;
        }

        return coordJob;
    }

    protected String getCoordJobXmlForWaiting(String testFileName, String testDir) {
        try {
            Reader reader = IOUtils.getResourceAsReader(testFileName, -1);
            String appXml = IOUtils.getReaderAsString(reader, -1);
            appXml = appXml.replaceAll("#testDir", testDir);
            return appXml;
        }
        catch (IOException ioe) {
            throw new RuntimeException(XLog.format("Could not get " + testFileName, ioe));
        }
    }

    protected String getProcessingTZ() {
        return DateUtils.OOZIE_PROCESSING_TIMEZONE_DEFAULT;
    }

}
