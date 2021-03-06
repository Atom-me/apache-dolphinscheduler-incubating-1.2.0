/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dolphinscheduler.server.zk;

import org.apache.commons.lang.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.apache.curator.utils.ThreadUtils;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.ExecutionStatus;
import org.apache.dolphinscheduler.common.enums.ZKNodeType;
import org.apache.dolphinscheduler.common.model.Server;
import org.apache.dolphinscheduler.common.zk.AbstractZKClient;
import org.apache.dolphinscheduler.dao.AlertDao;
import org.apache.dolphinscheduler.dao.DaoFactory;
import org.apache.dolphinscheduler.dao.ProcessDao;
import org.apache.dolphinscheduler.dao.entity.ProcessInstance;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.server.utils.ProcessUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ThreadFactory;


/**
 * zookeeper master client
 * <p>
 * single instance
 */
public class ZKMasterClient extends AbstractZKClient {

    /**
     * logger
     */
    private static final Logger logger = LoggerFactory.getLogger(ZKMasterClient.class);

    /**
     * thread factory
     */
    private static final ThreadFactory defaultThreadFactory = ThreadUtils.newGenericThreadFactory("Master-Main-Thread");

    /**
     * master znode
     */
    private String masterZNode = null;

    /**
     * alert database access
     */
    private AlertDao alertDao = null;
    /**
     * flow database access
     */
    private ProcessDao processDao;

    /**
     * zkMasterClient
     */
    private static ZKMasterClient zkMasterClient = null;

    /**
     * master path children cache
     */
    private PathChildrenCache masterPathChildrenCache;

    /**
     * worker path children cache
     */
    private PathChildrenCache workerPathChildrenCache;

    /**
     * constructor
     *
     * @param processDao process dao
     */
    private ZKMasterClient(ProcessDao processDao) {
        this.processDao = processDao;
        init();
    }

    /**
     * default constructor
     */
    private ZKMasterClient() {
    }

    /**
     * get zkMasterClient
     *
     * @param processDao process dao
     * @return ZKMasterClient zookeeper master client
     */
    public static synchronized ZKMasterClient getZKMasterClient(ProcessDao processDao) {
        if (zkMasterClient == null) {
            zkMasterClient = new ZKMasterClient(processDao);
        }
        zkMasterClient.processDao = processDao;

        return zkMasterClient;
    }

    /**
     * init
     */
    public void init() {
        // init dao
        this.initDao();

        InterProcessMutex mutex = null;
        try {
            // create distributed lock with the root node path of the lock space as /dolphinscheduler/lock/failover/master
            String znodeLock = getMasterStartUpLockPath();
            mutex = new InterProcessMutex(zkClient, znodeLock);
            mutex.acquire();

            // init system znode
            // ?????? ZK masters??? workers??? dead-server??????
            this.initSystemZNode();

            // monitor master
            this.listenerMaster();

            // monitor worker
            this.listenerWorker();

            // register master?????????master??????
            // ?????????master ?????? ??????????????????
            // OSUtils.getHost(),
            // OSUtils.getProcessID(),
            // OSUtils.cpuUsage(),
            // OSUtils.memoryUsage(),
            // OSUtils.loadAverage(),
            // DateUtils.dateToString(now),
            // DateUtils.dateToString(now))
            this.registerMaster();

            // check if fault tolerance is required???failure and tolerance
            if (getActiveMasterNum() == 1) {
                failoverWorker(null, true);
                failoverMaster(null);
            }

        } catch (Exception e) {
            logger.error("master start up  exception : " + e.getMessage(), e);
        } finally {
            releaseMutex(mutex);
        }
    }

    @Override
    public void close() {
        try {
            if (masterPathChildrenCache != null) {
                masterPathChildrenCache.close();
            }
            if (workerPathChildrenCache != null) {
                workerPathChildrenCache.close();
            }
            super.close();
        } catch (Exception ignore) {
        }
    }


    /**
     * init dao
     */
    public void initDao() {
        this.alertDao = DaoFactory.getDaoInstance(AlertDao.class);
    }

    /**
     * get alert dao
     *
     * @return AlertDao
     */
    public AlertDao getAlertDao() {
        return alertDao;
    }


    /**
     * register master znode
     */
    public void registerMaster() {
        try {
            String serverPath = registerServer(ZKNodeType.MASTER);
            if (StringUtils.isEmpty(serverPath)) {
                System.exit(-1);
            }
            masterZNode = serverPath;
        } catch (Exception e) {
            logger.error("register master failure : " + e.getMessage(), e);
            System.exit(-1);
        }
    }


    /**
     * monitor master
     */
    public void listenerMaster() {

        /*
         * curator?????????Cache????????????zookeeper???????????????????????????
         * cache???curator???????????????????????????????????????????????????????????????????????????????????????????????????zookeeper????????????????????????
         *
         * pathChildrenCache??????????????????
         *  ??????????????????zookeeper???????????????????????????????????????
         *  ???????????? boolean cacheData ?????????????????????????????????
         *  ????????????????????????????????????????????????????????????????????????
         */
        masterPathChildrenCache = new PathChildrenCache(zkClient,
                                                        getZNodeParentPath(ZKNodeType.MASTER),
                                                        true,
                                                        defaultThreadFactory);

        try {
            masterPathChildrenCache.start();
            masterPathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
                    switch (event.getType()) {
                        case CHILD_ADDED:
                            logger.info("master node added : {}", event.getData().getPath());
                            break;
                        case CHILD_REMOVED:
                            String path = event.getData().getPath();
                            String serverHost = getHostByEventDataPath(path);
                            if (checkServerSelfDead(serverHost, ZKNodeType.MASTER)) {
                                return;
                            }
                            removeZKNodePath(path, ZKNodeType.MASTER, true);
                            break;
                        case CHILD_UPDATED:
                            break;
                        default:
                            break;
                    }
                }
            });
        } catch (Exception e) {
            logger.error("monitor master failed : " + e.getMessage(), e);
        }
    }

    /**
     * remove zookeeper node path
     *
     * @param path       zookeeper node path
     * @param zkNodeType zookeeper node type
     * @param failover   is failover
     */
    private void removeZKNodePath(String path, ZKNodeType zkNodeType, boolean failover) {
        logger.info("{} node deleted : {}", zkNodeType.toString(), path);
        InterProcessMutex mutex = null;
        try {
            String failoverPath = getFailoverLockPath(zkNodeType);
            // create a distributed lock
            mutex = new InterProcessMutex(getZkClient(), failoverPath);
            mutex.acquire();

            String serverHost = getHostByEventDataPath(path);
            // handle dead server
            handleDeadServer(path, zkNodeType, Constants.ADD_ZK_OP);
            //alert server down.
            alertServerDown(serverHost, zkNodeType);
            //failover server
            if (failover) {
                failoverServerWhenDown(serverHost, zkNodeType);
            }
        } catch (Exception e) {
            logger.error("{} server failover failed.", zkNodeType.toString());
            logger.error("failover exception : " + e.getMessage(), e);
        } finally {
            releaseMutex(mutex);
        }
    }

    /**
     * failover server when server down
     *
     * @param serverHost server host
     * @param zkNodeType zookeeper node type
     * @throws Exception exception
     */
    private void failoverServerWhenDown(String serverHost, ZKNodeType zkNodeType) throws Exception {
        if (StringUtils.isEmpty(serverHost)) {
            return;
        }
        switch (zkNodeType) {
            case MASTER:
                failoverMaster(serverHost);
                break;
            case WORKER:
                failoverWorker(serverHost, true);
            default:
                break;
        }
    }

    /**
     * get failover lock path
     *
     * @param zkNodeType zookeeper node type
     * @return fail over lock path
     */
    private String getFailoverLockPath(ZKNodeType zkNodeType) {

        switch (zkNodeType) {
            case MASTER:
                return getMasterFailoverLockPath();
            case WORKER:
                return getWorkerFailoverLockPath();
            default:
                return "";
        }
    }

    /**
     * send alert when server down
     *
     * @param serverHost server host
     * @param zkNodeType zookeeper node type
     */
    private void alertServerDown(String serverHost, ZKNodeType zkNodeType) {

        String serverType = zkNodeType.toString();
        for (int i = 0; i < Constants.DOLPHINSCHEDULER_WARN_TIMES_FAILOVER; i++) {
            alertDao.sendServerStopedAlert(1, serverHost, serverType);
        }
    }

    /**
     * monitor worker
     */
    public void listenerWorker() {
        workerPathChildrenCache = new PathChildrenCache(zkClient,
                getZNodeParentPath(ZKNodeType.WORKER), true, defaultThreadFactory);
        try {
            workerPathChildrenCache.start();
            workerPathChildrenCache.getListenable().addListener(new PathChildrenCacheListener() {
                @Override
                public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) {
                    switch (event.getType()) {
                        case CHILD_ADDED:
                            logger.info("node added : {}", event.getData().getPath());
                            break;
                        case CHILD_REMOVED:
                            String path = event.getData().getPath();
                            logger.info("node deleted : {}", event.getData().getPath());
                            removeZKNodePath(path, ZKNodeType.WORKER, true);
                            break;
                        default:
                            break;
                    }
                }
            });
        } catch (Exception e) {
            logger.error("listener worker failed : " + e.getMessage(), e);
        }
    }


    /**
     * get master znode
     *
     * @return master zookeeper node
     */
    public String getMasterZNode() {
        return masterZNode;
    }

    /**
     * task needs failover if task start before worker starts
     *
     * @param taskInstance task instance
     * @return true if task instance need fail over
     */
    private boolean checkTaskInstanceNeedFailover(TaskInstance taskInstance) throws Exception {

        boolean taskNeedFailover = true;

        //now no host will execute this task instance,so no need to failover the task
        if (taskInstance.getHost() == null) {
            return false;
        }

        // if the worker node exists in zookeeper, we must check the task starts after the worker
        if (checkZKNodeExists(taskInstance.getHost(), ZKNodeType.WORKER)) {
            //if task start after worker starts, there is no need to failover the task.
            if (checkTaskAfterWorkerStart(taskInstance)) {
                taskNeedFailover = false;
            }
        }
        return taskNeedFailover;
    }

    /**
     * check task start after the worker server starts.
     *
     * @param taskInstance task instance
     * @return true if task instance start time after worker server start date
     */
    private boolean checkTaskAfterWorkerStart(TaskInstance taskInstance) {
        if (StringUtils.isEmpty(taskInstance.getHost())) {
            return false;
        }
        Date workerServerStartDate = null;
        List<Server> workerServers = getServersList(ZKNodeType.WORKER);
        for (Server workerServer : workerServers) {
            if (workerServer.getHost().equals(taskInstance.getHost())) {
                workerServerStartDate = workerServer.getCreateTime();
                break;
            }
        }

        if (workerServerStartDate != null) {
            return taskInstance.getStartTime().after(workerServerStartDate);
        } else {
            return false;
        }
    }

    /**
     * failover worker tasks
     *
     * 1. kill yarn job if there are yarn jobs in tasks.
     * 2. change task state from running to need failover.
     * 3. failover all tasks when workerHost is null
     * @param workerHost worker host
     */

    /**
     * failover worker tasks
     * <p>
     * 1. kill yarn job if there are yarn jobs in tasks.
     * 2. change task state from running to need failover.
     * 3. failover all tasks when workerHost is null
     *
     * @param workerHost           worker host
     * @param needCheckWorkerAlive need check worker alive
     * @throws Exception exception
     */
    private void failoverWorker(String workerHost, boolean needCheckWorkerAlive) throws Exception {
        logger.info("start worker[{}] failover ...", workerHost);

        List<TaskInstance> needFailoverTaskInstanceList = processDao.queryNeedFailoverTaskInstances(workerHost);
        for (TaskInstance taskInstance : needFailoverTaskInstanceList) {
            if (needCheckWorkerAlive) {
                if (!checkTaskInstanceNeedFailover(taskInstance)) {
                    continue;
                }
            }

            ProcessInstance instance = processDao.findProcessInstanceDetailById(taskInstance.getProcessInstanceId());
            if (instance != null) {
                taskInstance.setProcessInstance(instance);
            }
            // only kill yarn job if exists , the local thread has exited
            ProcessUtils.killYarnJob(taskInstance);

            taskInstance.setState(ExecutionStatus.NEED_FAULT_TOLERANCE);
            processDao.saveTaskInstance(taskInstance);
        }
        logger.info("end worker[{}] failover ...", workerHost);
    }

    /**
     * failover master tasks
     *
     * @param masterHost master host
     */
    private void failoverMaster(String masterHost) {
        logger.info("start master failover ...");

        List<ProcessInstance> needFailoverProcessInstanceList = processDao.queryNeedFailoverProcessInstances(masterHost);

        //updateProcessInstance host is null and insert into command
        for (ProcessInstance processInstance : needFailoverProcessInstanceList) {
            processDao.processNeedFailoverProcessInstances(processInstance);
        }

        logger.info("master failover end");
    }

}
