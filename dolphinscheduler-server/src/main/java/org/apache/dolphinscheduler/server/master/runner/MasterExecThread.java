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
package org.apache.dolphinscheduler.server.master.runner;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.enums.*;
import org.apache.dolphinscheduler.common.graph.DAG;
import org.apache.dolphinscheduler.common.model.TaskNode;
import org.apache.dolphinscheduler.common.model.TaskNodeRelation;
import org.apache.dolphinscheduler.common.process.ProcessDag;
import org.apache.dolphinscheduler.common.thread.Stopper;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.dao.DaoFactory;
import org.apache.dolphinscheduler.dao.ProcessDao;
import org.apache.dolphinscheduler.dao.entity.ProcessInstance;
import org.apache.dolphinscheduler.dao.entity.TaskInstance;
import org.apache.dolphinscheduler.dao.utils.DagHelper;
import org.apache.dolphinscheduler.server.utils.AlertManager;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.dolphinscheduler.common.utils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.apache.dolphinscheduler.common.Constants.*;

/**
 * master exec thread,split dag
 */
public class MasterExecThread implements Runnable {

    /**
     * logger of MasterExecThread
     */
    private static final Logger logger = LoggerFactory.getLogger(MasterExecThread.class);

    /**
     * process instance
     */
    private ProcessInstance processInstance;

    /**
     *  runing TaskNode
     */
    private final Map<MasterBaseTaskExecThread,Future<Boolean>> activeTaskNode = new ConcurrentHashMap<MasterBaseTaskExecThread,Future<Boolean>>();

    /**
     * task exec service
     */
    private final ExecutorService taskExecService;

    /**
     * submit failure nodes
     */
    private Boolean taskFailedSubmit = false;

    /**
     * recover node id list
     */
    private List<TaskInstance> recoverNodeIdList = new ArrayList<>();

    /**
     * error task list
     */
    private Map<String,TaskInstance> errorTaskList = new ConcurrentHashMap<>();

    /**
     * complete task list
     */
    private Map<String, TaskInstance> completeTaskList = new ConcurrentHashMap<>();

    /**
     * ready to submit task list
     */
    private Map<String, TaskInstance> readyToSubmitTaskList = new ConcurrentHashMap<>();

    /**
     * depend failed task map
     */
    private Map<String, TaskInstance> dependFailedTask = new ConcurrentHashMap<>();

    /**
     * forbidden task map
     */
    private Map<String, TaskNode> forbiddenTaskList = new ConcurrentHashMap<>();

    /**
     * recover tolerance fault task list
     */
    private List<TaskInstance> recoverToleranceFaultTaskList = new ArrayList<>();

    /**
     * alert manager
     */
    private AlertManager alertManager = new AlertManager();

    /**
     * the object of DAG
     */
    private DAG<String,TaskNode,TaskNodeRelation> dag;

    /**
     *  process dao
     */
    private ProcessDao processDao;

    /**
     * load configuration file
     */
    private static Configuration conf;

    /**
     * constructor of MasterExecThread
     * @param processInstance   process instance
     * @param processDao        process dao
     */
    public MasterExecThread(ProcessInstance processInstance,ProcessDao processDao){
        this.processDao = processDao;

        this.processInstance = processInstance;

        int masterTaskExecNum = conf.getInt(Constants.MASTER_EXEC_TASK_THREADS,
                Constants.defaultMasterTaskExecNum);
        this.taskExecService = ThreadUtils.newDaemonFixedThreadExecutor("Master-Task-Exec-Thread",
                masterTaskExecNum);
    }


    static {
        try {
            conf = new PropertiesConfiguration(Constants.MASTER_PROPERTIES_PATH);
        }catch (ConfigurationException e){
            logger.error("load configuration failed : " + e.getMessage(),e);
            System.exit(1);
        }
    }

    @Override
    public void run() {

        // process instance is null
        if (processInstance == null){
            logger.info("process instance is not exists");
            return;
        }

        // check to see if it's done
        if (processInstance.getState().typeIsFinished()){
            logger.info("process instance is done : {}",processInstance.getId());
            return;
        }

        try {
            if (processInstance.isComplementData() &&  Flag.NO == processInstance.getIsSubProcess()){
                // sub process complement data
                executeComplementProcess();
            }else{
                // execute flow
                executeProcess();
            }
        }catch (Exception e){
            logger.error("master exec thread exception: " + e.getMessage(), e);
            logger.error("process execute failed, process id:{}", processInstance.getId());
            processInstance.setState(ExecutionStatus.FAILURE);
            processInstance.setEndTime(new Date());
            processDao.updateProcessInstance(processInstance);
        }finally {
            taskExecService.shutdown();
            // post handle
            postHandle();
        }
    }

    /**
     * execute process
     * @throws Exception excpetion
     */
    private void executeProcess() throws Exception {
        prepareProcess();
        // ???????????????????????????????????? mysql ???ZK task ??????
        runProcess();
        endProcess();
    }

    /**
     * execute complement process
     * @throws Exception excpetion
     */
    private void executeComplementProcess() throws Exception {

        Map<String, String> cmdParam = JSONUtils.toMap(processInstance.getCommandParam());

        Date startDate = DateUtils.getScheduleDate(cmdParam.get(CMDPARAM_COMPLEMENT_DATA_START_DATE));
        Date endDate = DateUtils.getScheduleDate(cmdParam.get(CMDPARAM_COMPLEMENT_DATA_END_DATE));
        processDao.saveProcessInstance(processInstance);
        Date scheduleDate = processInstance.getScheduleTime();

        if(scheduleDate == null){
            scheduleDate = startDate;
        }

        while(Stopper.isRunning()){
            // prepare dag and other info
            prepareProcess();

            if(dag == null){
                logger.error("process {} dag is null, please check out parameters",
                        processInstance.getId());
                processInstance.setState(ExecutionStatus.SUCCESS);
                processDao.updateProcessInstance(processInstance);
                return;
            }

            // execute process ,waiting for end
            runProcess();

            // process instace failure ???no more complements
            if(!processInstance.getState().typeIsSuccess()){
                logger.info("process {} state {}, complement not completely!",
                        processInstance.getId(), processInstance.getState());
                break;
            }

            //  current process instance sucess ???next execute
            scheduleDate = DateUtils.getSomeDay(scheduleDate, 1);
            if(scheduleDate.after(endDate)){
                // all success
                logger.info("process {} complement completely!", processInstance.getId());
                break;
            }

            logger.info("process {} start to complement {} data",
                    processInstance.getId(), DateUtils.dateToString(scheduleDate));
            // execute next process instance complement data
            processInstance.setScheduleTime(scheduleDate);
            if(cmdParam.containsKey(Constants.CMDPARAM_RECOVERY_START_NODE_STRING)){
                cmdParam.remove(Constants.CMDPARAM_RECOVERY_START_NODE_STRING);
                processInstance.setCommandParam(JSONUtils.toJson(cmdParam));
            }

            List<TaskInstance> taskInstanceList = processDao.findValidTaskListByProcessId(processInstance.getId());
            for(TaskInstance taskInstance : taskInstanceList){
                taskInstance.setFlag(Flag.NO);
                processDao.updateTaskInstance(taskInstance);
            }
            processInstance.setState(ExecutionStatus.RUNNING_EXEUTION);
            processInstance.setGlobalParams(ParameterUtils.curingGlobalParams(
                    processInstance.getProcessDefinition().getGlobalParamMap(),
                    processInstance.getProcessDefinition().getGlobalParamList(),
                    CommandType.COMPLEMENT_DATA, processInstance.getScheduleTime()));

            processDao.saveProcessInstance(processInstance);
        }

        // flow end
        endProcess();

    }


    /**
     * prepare process parameter
     * @throws Exception excpetion
     */
    private void prepareProcess() throws Exception {
        // init task queue
        initTaskQueue();

        // gen process dag
        buildFlowDag();
        logger.info("prepare process :{} end", processInstance.getId());
    }


    /**
     * process end handle
     */
    private void endProcess() {
        processInstance.setEndTime(new Date());
        processDao.updateProcessInstance(processInstance);
        if(processInstance.getState().typeIsWaittingThread()){
            processDao.createRecoveryWaitingThreadCommand(null, processInstance);
        }
        List<TaskInstance> taskInstances = processDao.findValidTaskListByProcessId(processInstance.getId());
        alertManager.sendAlertProcessInstance(processInstance, taskInstances);
    }


    /**
     *  generate process dag
     * @throws Exception excpetion
     */
    private void buildFlowDag() throws Exception {
        recoverNodeIdList = getStartTaskInstanceList(processInstance.getCommandParam());

        forbiddenTaskList = DagHelper.getForbiddenTaskNodeMaps(processInstance.getProcessInstanceJson());
        // generate process to get DAG info
        List<String> recoveryNameList = getRecoveryNodeNameList();
        List<String> startNodeNameList = parseStartNodeName(processInstance.getCommandParam());
        ProcessDag processDag = generateFlowDag(processInstance.getProcessInstanceJson(),
                startNodeNameList, recoveryNameList, processInstance.getTaskDependType());
        if(processDag == null){
            logger.error("processDag is null");
            return;
        }
        // generate process dag
        dag = DagHelper.buildDagGraph(processDag);

    }

    /**
     * init task queue
     */
    private void initTaskQueue(){

        taskFailedSubmit = false;
        activeTaskNode.clear();
        dependFailedTask.clear();
        completeTaskList.clear();
        errorTaskList.clear();
        List<TaskInstance> taskInstanceList = processDao.findValidTaskListByProcessId(processInstance.getId());
        for(TaskInstance task : taskInstanceList){
            if(task.isTaskComplete()){
                completeTaskList.put(task.getName(), task);
            }
            if(task.getState().typeIsFailure() && !task.taskCanRetry()){
                errorTaskList.put(task.getName(), task);
            }
        }
    }

    /**
     * process post handle
     */
    private void postHandle() {
        logger.info("develop mode is: {}", CommonUtils.isDevelopMode());

        if (!CommonUtils.isDevelopMode()) {
            // get exec dir
            String execLocalPath = org.apache.dolphinscheduler.common.utils.FileUtils
                    .getProcessExecDir(processInstance.getProcessDefinition().getProjectId(),
                            processInstance.getProcessDefinitionId(),
                            processInstance.getId());

            try {
                FileUtils.deleteDirectory(new File(execLocalPath));
            } catch (IOException e) {
                logger.error("delete exec dir failed : " + e.getMessage(), e);
            }
        }
    }

    /**
     * submit task to execute
     * @param taskInstance task instance
     * @return TaskInstance
     */
    private TaskInstance submitTaskExec(TaskInstance taskInstance) {
        MasterBaseTaskExecThread abstractExecThread = null;
        if(taskInstance.isSubProcess()){
            abstractExecThread = new SubProcessTaskExecThread(taskInstance, processInstance);
        }else {
            //????????????????????? mysql????????????????????? ???ZK task?????? ????????? MasterTaskExecThread call ??????
            abstractExecThread = new MasterTaskExecThread(taskInstance, processInstance);
        }
        Future<Boolean> future = taskExecService.submit(abstractExecThread);
        activeTaskNode.putIfAbsent(abstractExecThread, future);
        return abstractExecThread.getTaskInstance();
    }

    /**
     * find task instance in db.
     * in case submit more than one same name task in the same time.
     * @param taskName task name
     * @return TaskInstance
     */
    private TaskInstance findTaskIfExists(String taskName){
        List<TaskInstance> taskInstanceList = processDao.findValidTaskListByProcessId(this.processInstance.getId());
        for(TaskInstance taskInstance : taskInstanceList){
            if(taskInstance.getName().equals(taskName)){
                return taskInstance;
            }
        }
        return null;
    }

    /**
     * encapsulation task
     * @param processInstance   process instance
     * @param nodeName          node name
     * @return TaskInstance
     */
    private TaskInstance createTaskInstance(ProcessInstance processInstance, String nodeName,
                                            TaskNode taskNode, String parentNodeName) {

        TaskInstance taskInstance = findTaskIfExists(nodeName);
        if(taskInstance == null){
            taskInstance = new TaskInstance();
            // task name
            taskInstance.setName(nodeName);
            // process instance define id
            taskInstance.setProcessDefinitionId(processInstance.getProcessDefinitionId());
            // task instance state
            taskInstance.setState(ExecutionStatus.SUBMITTED_SUCCESS);
            // process instance id
            taskInstance.setProcessInstanceId(processInstance.getId());
            // task instance node json
            taskInstance.setTaskJson(JSONObject.toJSONString(taskNode));
            // task instance type
            taskInstance.setTaskType(taskNode.getType());
            // task instance whether alert
            taskInstance.setAlertFlag(Flag.NO);

            // task instance start time
            taskInstance.setStartTime(new Date());

            // task instance flag
            taskInstance.setFlag(Flag.YES);

            // task instance retry times
            taskInstance.setRetryTimes(0);

            // max task instance retry times
            taskInstance.setMaxRetryTimes(taskNode.getMaxRetryTimes());

            // retry task instance interval
            taskInstance.setRetryInterval(taskNode.getRetryInterval());

            // task instance priority
            if(taskNode.getTaskInstancePriority() == null){
                taskInstance.setTaskInstancePriority(Priority.MEDIUM);
            }else{
                taskInstance.setTaskInstancePriority(taskNode.getTaskInstancePriority());
            }

            int workerGroupId = taskNode.getWorkerGroupId();
            taskInstance.setWorkerGroupId(workerGroupId);

        }
        return taskInstance;
    }

    /**
     * get post task instance by node
     * @param dag               dag
     * @param parentNodeName    parent node name
     * @return task instance list
     */
    private List<TaskInstance> getPostTaskInstanceByNode(DAG<String, TaskNode, TaskNodeRelation> dag, String parentNodeName){

        List<TaskInstance> postTaskList = new ArrayList<>();
        Collection<String> startVertex = DagHelper.getStartVertex(parentNodeName, dag, completeTaskList);
        if(startVertex == null){
            return postTaskList;
        }

        for (String nodeName : startVertex){
            // encapsulation task instance
            TaskInstance taskInstance = createTaskInstance(processInstance, nodeName ,
                    dag.getNode(nodeName),parentNodeName);
            postTaskList.add(taskInstance);
        }
        return postTaskList;
    }

    /**
     * return start task node list
     * @return task instance list
     */
    private List<TaskInstance> getStartSubmitTaskList(){

        // ????????????????????????
        List<TaskInstance> startTaskList = getPostTaskInstanceByNode(dag, null);

        HashMap<String, TaskInstance> successTaskMaps = new HashMap<>();
        List<TaskInstance> resultList = new ArrayList<>();
        while(Stopper.isRunning()){
            for(TaskInstance task : startTaskList){
                if(task.getState().typeIsSuccess()){
                    successTaskMaps.put(task.getName(), task);
                }else if(!completeTaskList.containsKey(task.getName()) && !errorTaskList.containsKey(task.getName())){
                    resultList.add(task);
                }
            }
            startTaskList.clear();
            if(successTaskMaps.size() == 0){
                break;
            }

            Set<String> taskNameKeys = successTaskMaps.keySet();
            for(String taskName : taskNameKeys){
                startTaskList.addAll(getPostTaskInstanceByNode(dag, taskName));
            }
            successTaskMaps.clear();
        }
        return resultList;
    }

    /**
     * submit post node
     * @param parentNodeName parent node name
     */
    private void submitPostNode(String parentNodeName){

        List<TaskInstance> submitTaskList = null;
        if(parentNodeName == null){
            // ????????????????????????
            submitTaskList = getStartSubmitTaskList();
        }else{
            submitTaskList = getPostTaskInstanceByNode(dag, parentNodeName);
        }
        // if previous node success , post node submit
        // ?????????????????????????????????????????????
        for(TaskInstance task : submitTaskList){
            if(readyToSubmitTaskList.containsKey(task.getName())){
                continue;
            }

            if(completeTaskList.containsKey(task.getName())){
                logger.info("task {} has already run success", task.getName());
                continue;
            }
            if(task.getState().typeIsPause() || task.getState().typeIsCancel()){
                logger.info("task {} stopped, the state is {}", task.getName(), task.getState().toString());
            }else{
                addTaskToStandByList(task);
            }
        }
    }

    /**
     * determine whether the dependencies of the task node are complete
     * @return DependResult
     */
    private DependResult isTaskDepsComplete(String taskName) {

        Collection<String> startNodes = dag.getBeginNode();
        // ff the vertex returns true directly
        if(startNodes.contains(taskName)){
            return DependResult.SUCCESS;
        }

        TaskNode taskNode = dag.getNode(taskName);
        List<String> depsNameList = taskNode.getDepList();
        for(String depsNode : depsNameList ){

            if(forbiddenTaskList.containsKey(depsNode)){
                continue;
            }
            // dependencies must be fully completed
            if(!completeTaskList.containsKey(depsNode)){
                return DependResult.WAITING;
            }
            ExecutionStatus taskState = completeTaskList.get(depsNode).getState();
            if(taskState.typeIsFailure()){
                return DependResult.FAILED;
            }
            if(taskState.typeIsPause() || taskState.typeIsCancel()){
                return DependResult.WAITING;
            }
        }

        logger.info("taskName: {} completeDependTaskList: {}", taskName, Arrays.toString(completeTaskList.keySet().toArray()));

        return DependResult.SUCCESS;
    }


    /**
     * query task instance by complete state
     * @param state state
     * @return task isntance list
     */
    private List<TaskInstance> getCompleteTaskByState(ExecutionStatus state){
        List<TaskInstance> resultList = new ArrayList<>();
        for (Map.Entry<String, TaskInstance> entry: completeTaskList.entrySet()) {
            if(entry.getValue().getState() == state){
                resultList.add(entry.getValue());
            }
        }
        return resultList;
    }

    /**
     *  where there are ongoing tasks
     * @param state state
     * @return ExecutionStatus
     */
    private ExecutionStatus runningState(ExecutionStatus state){
        if(state == ExecutionStatus.READY_STOP ||
                state == ExecutionStatus.READY_PAUSE ||
                state == ExecutionStatus.WAITTING_THREAD){
            // if the running task is not completed, the state remains unchanged
            return state;
        }else{
            return ExecutionStatus.RUNNING_EXEUTION;
        }
    }

    /**
     * exists failure task,contains submit failure???dependency failure,execute failure(retry after)
     *
     * @return Boolean whether has failed task
     */
    private Boolean hasFailedTask(){

        if(this.taskFailedSubmit){
            return true;
        }
        if(this.errorTaskList.size() > 0){
            return true;
        }
        return this.dependFailedTask.size() > 0;
    }

    /**
     * process instance failure
     *
     * @return Boolean whether process instance failed
     */
    private Boolean processFailed(){
        if(hasFailedTask()) {
            if(processInstance.getFailureStrategy() == FailureStrategy.END){
                return true;
            }
            if (processInstance.getFailureStrategy() == FailureStrategy.CONTINUE) {
                return readyToSubmitTaskList.size() == 0 || activeTaskNode.size() == 0;
            }
        }
        return false;
    }

    /**
     * whether task for waiting thread
     * @return Boolean whether has waiting thread task
     */
    private Boolean hasWaitingThreadTask(){
        List<TaskInstance> waitingList = getCompleteTaskByState(ExecutionStatus.WAITTING_THREAD);
        return waitingList.size() > 0;
    }

    /**
     * prepare for pause
     * 1???failed retry task in the preparation queue , returns to failure directly
     * 2???exists pause task???complement not completed, pending submission of tasks, return to suspension
     * 3???success
     * @return ExecutionStatus
     */
    private ExecutionStatus processReadyPause(){
        if(hasRetryTaskInStandBy()){
            return ExecutionStatus.FAILURE;
        }

        List<TaskInstance> pauseList = getCompleteTaskByState(ExecutionStatus.PAUSE);
        if(pauseList.size() > 0
                || !isComplementEnd()
                || readyToSubmitTaskList.size() > 0){
            return ExecutionStatus.PAUSE;
        }else{
            return ExecutionStatus.SUCCESS;
        }
    }


    /**
     * generate the latest process instance status by the tasks state
     * @return process instance execution status
     */
    private ExecutionStatus getProcessInstanceState(){
        ProcessInstance instance = processDao.findProcessInstanceById(processInstance.getId());
        ExecutionStatus state = instance.getState();

        if(activeTaskNode.size() > 0){
            return runningState(state);
        }
        // process failure
        if(processFailed()){
            return ExecutionStatus.FAILURE;
        }

        // waiting thread
        if(hasWaitingThreadTask()){
            return ExecutionStatus.WAITTING_THREAD;
        }

        // pause
        if(state == ExecutionStatus.READY_PAUSE){
            return processReadyPause();
        }

        // stop
        if(state == ExecutionStatus.READY_STOP){
            List<TaskInstance> stopList = getCompleteTaskByState(ExecutionStatus.STOP);
            List<TaskInstance> killList = getCompleteTaskByState(ExecutionStatus.KILL);
            if(stopList.size() > 0 || killList.size() > 0 || !isComplementEnd()){
                return ExecutionStatus.STOP;
            }else{
                return ExecutionStatus.SUCCESS;
            }
        }

        // success
        if(state == ExecutionStatus.RUNNING_EXEUTION){
            if(readyToSubmitTaskList.size() > 0){
                //tasks currently pending submission, no retries, indicating that depend is waiting to complete
                return ExecutionStatus.RUNNING_EXEUTION;
            }else{
                //  if the waiting queue is empty and the status is in progress, then success
                return ExecutionStatus.SUCCESS;
            }
        }

        return state;
    }

    /**
     * whether complement end
     * @return Boolean whether is complement end
     */
    private Boolean isComplementEnd() {
        if(!processInstance.isComplementData()){
            return true;
        }

        try {
            Map<String, String> cmdParam = JSONUtils.toMap(processInstance.getCommandParam());
            Date endTime = DateUtils.getScheduleDate(cmdParam.get(CMDPARAM_COMPLEMENT_DATA_END_DATE));
            return processInstance.getScheduleTime().equals(endTime);
        } catch (Exception e) {
            logger.error("complement end failed : " + e.getMessage(),e);
            return false;
        }
    }

    /**
     * updateProcessInstance process instance state
     * after each batch of tasks is executed, the status of the process instance is updated
     */
    private void updateProcessInstanceState() {
        ExecutionStatus state = getProcessInstanceState();
        if(processInstance.getState() != state){
            logger.info(
                    "work flow process instance [id: {}, name:{}], state change from {} to {}, cmd type: {}",
                    processInstance.getId(), processInstance.getName(),
                    processInstance.getState().toString(), state.toString(),
                    processInstance.getCommandType().toString());
            processInstance.setState(state);
            ProcessInstance instance = processDao.findProcessInstanceById(processInstance.getId());
            instance.setState(state);
            instance.setProcessDefinition(processInstance.getProcessDefinition());
            processDao.updateProcessInstance(instance);
            processInstance = instance;
        }
    }

    /**
     * get task dependency result
     * @param taskInstance task instance
     * @return DependResult
     */
    private DependResult getDependResultForTask(TaskInstance taskInstance){
        DependResult inner = isTaskDepsComplete(taskInstance.getName());
        return inner;
    }

    /**
     * add task to standy list
     * @param taskInstance task instance
     */
    private void addTaskToStandByList(TaskInstance taskInstance){
        logger.info("add task to stand by list: {}", taskInstance.getName());
        readyToSubmitTaskList.putIfAbsent(taskInstance.getName(), taskInstance);
    }

    /**
     * remove task from stand by list
     * @param taskInstance task instance
     */
    private void removeTaskFromStandbyList(TaskInstance taskInstance){
        logger.info("remove task from stand by list: {}", taskInstance.getName());
        readyToSubmitTaskList.remove(taskInstance.getName());
    }

    /**
     * has retry task in standby
     * @return Boolean whether has retry task in standby
     */
    private Boolean hasRetryTaskInStandBy(){
        for (Map.Entry<String, TaskInstance> entry: readyToSubmitTaskList.entrySet()) {
            if(entry.getValue().getState().typeIsFailure()){
                return true;
            }
        }
        return false;
    }

    /**
     * submit and watch the tasks, until the work flow stop
     */
    private void runProcess(){
        // submit start node
        submitPostNode(null);
        boolean sendTimeWarning = false;
        while(!processInstance.IsProcessInstanceStop()){

            // send warning email if process time out.
            if( !sendTimeWarning && checkProcessTimeOut(processInstance) ){
                alertManager.sendProcessTimeoutAlert(processInstance,
                        processDao.findProcessDefineById(processInstance.getProcessDefinitionId()));
                sendTimeWarning = true;
            }
            for(Map.Entry<MasterBaseTaskExecThread,Future<Boolean>> entry: activeTaskNode.entrySet()) {
                Future<Boolean> future = entry.getValue();
                TaskInstance task  = entry.getKey().getTaskInstance();

                if(!future.isDone()){
                    continue;
                }
                // node monitor thread complete
                activeTaskNode.remove(entry.getKey());
                if(task == null){
                    this.taskFailedSubmit = true;
                    continue;
                }
                logger.info("task :{}, id:{} complete, state is {} ",
                        task.getName(), task.getId(), task.getState().toString());
                // node success , post node submit
                if(task.getState() == ExecutionStatus.SUCCESS){
                    completeTaskList.put(task.getName(), task);
                    submitPostNode(task.getName());
                    continue;
                }
                // node fails, retry first, and then execute the failure process
                if(task.getState().typeIsFailure()){
                    if(task.getState() == ExecutionStatus.NEED_FAULT_TOLERANCE){
                        this.recoverToleranceFaultTaskList.add(task);
                    }
                    if(task.taskCanRetry()){
                        addTaskToStandByList(task);
                    }else{
                        // node failure, based on failure strategy
                        errorTaskList.put(task.getName(), task);
                        completeTaskList.put(task.getName(), task);
                        if(processInstance.getFailureStrategy() == FailureStrategy.END){
                            killTheOtherTasks();
                        }
                    }
                    continue;
                }
                // other status stop/pause
                completeTaskList.put(task.getName(), task);
            }
            // send alert
            if(this.recoverToleranceFaultTaskList.size() > 0){
                alertManager.sendAlertWorkerToleranceFault(processInstance, recoverToleranceFaultTaskList);
                this.recoverToleranceFaultTaskList.clear();
            }
            // updateProcessInstance completed task status
            // failure priority is higher than pause
            // if a task fails, other suspended tasks need to be reset kill
            if(errorTaskList.size() > 0){
                for(Map.Entry<String, TaskInstance> entry: completeTaskList.entrySet()) {
                    TaskInstance completeTask = entry.getValue();
                    if(completeTask.getState()== ExecutionStatus.PAUSE){
                        completeTask.setState(ExecutionStatus.KILL);
                        completeTaskList.put(entry.getKey(), completeTask);
                        processDao.updateTaskInstance(completeTask);
                    }
                }
            }
            // ???????????????????????????????????????????????? mysql zk ???????????????
            if(canSubmitTaskToQueue()){
                submitStandByTask();
            }
            try {
                Thread.sleep(Constants.SLEEP_TIME_MILLIS);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(),e);
            }
            updateProcessInstanceState();
        }

        logger.info("process:{} end, state :{}", processInstance.getId(), processInstance.getState());
    }

    /**
     * whether check process time out
     * @param processInstance task instance
     * @return true if time out of process instance > running time of process instance
     */
    private boolean checkProcessTimeOut(ProcessInstance processInstance) {
        if(processInstance.getTimeout() == 0 ){
            return false;
        }

        Date now = new Date();
        long runningTime =  DateUtils.diffMin(now, processInstance.getStartTime());

        if(runningTime > processInstance.getTimeout()){
            return true;
        }
        return false;
    }

    /**
     * whether can submit task to queue
     * @return boolean
     */
    private boolean canSubmitTaskToQueue() {
        return OSUtils.checkResource(conf, true);
    }


    /**
     * close the on going tasks
     */
    private void killTheOtherTasks() {

        logger.info("kill called on process instance id: {}, num: {}", processInstance.getId(),
                activeTaskNode.size());
        for (Map.Entry<MasterBaseTaskExecThread, Future<Boolean>> entry : activeTaskNode.entrySet()) {
            MasterBaseTaskExecThread taskExecThread = entry.getKey();
            Future<Boolean> future = entry.getValue();

            TaskInstance taskInstance = taskExecThread.getTaskInstance();
            taskInstance = processDao.findTaskInstanceById(taskInstance.getId());
            if(taskInstance.getState().typeIsFinished()){
                continue;
            }

            if (!future.isDone()) {
                // record kill info
                logger.info("kill process instance, id: {}, task: {}", processInstance.getId(), taskExecThread.getTaskInstance().getId());

                //  kill node
                taskExecThread.kill();
            }
        }
    }

    /**
     * whether the retry interval is timed out
     * @param taskInstance task instance
     * @return Boolean
     */
    private Boolean retryTaskIntervalOverTime(TaskInstance taskInstance){
        if(taskInstance.getState() != ExecutionStatus.FAILURE){
            return Boolean.TRUE;
        }
        if(taskInstance.getId() == 0 ||
                taskInstance.getMaxRetryTimes() ==0 ||
                taskInstance.getRetryInterval() == 0 ){
            return Boolean.TRUE;
        }
        Date now = new Date();
        long failedTimeInterval = DateUtils.differSec(now, taskInstance.getEndTime());
        // task retry does not over time, return false
        if(taskInstance.getRetryInterval() * SEC_2_MINUTES_TIME_UNIT >= failedTimeInterval){
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * handling the list of tasks to be submitted
     */
    private void submitStandByTask(){
        for(Map.Entry<String, TaskInstance> entry: readyToSubmitTaskList.entrySet()) {
            TaskInstance task = entry.getValue();
            DependResult dependResult = getDependResultForTask(task);
            //?????????????????????????????????????????????????????? mysql????????????????????? ???ZK task??????
            if(DependResult.SUCCESS == dependResult){
                if(retryTaskIntervalOverTime(task)){
                    submitTaskExec(task);
                    removeTaskFromStandbyList(task);
                }
            }else if(DependResult.FAILED == dependResult){
                // if the dependency fails, the current node is not submitted and the state changes to failure.
                dependFailedTask.put(entry.getKey(), task);
                removeTaskFromStandbyList(task);
                logger.info("task {},id:{} depend result : {}",task.getName(), task.getId(), dependResult);
            }
        }
    }

    /**
     * get recovery task instance
     * @param taskId task id
     * @return recovery task instance
     */
    private TaskInstance getRecoveryTaskInstance(String taskId){
        if(!StringUtils.isNotEmpty(taskId)){
            return null;
        }
        try {
            Integer intId = Integer.valueOf(taskId);
            TaskInstance task = processDao.findTaskInstanceById(intId);
            if(task == null){
                logger.error("start node id cannot be found: {}",  taskId);
            }else {
                return task;
            }
        }catch (Exception e){
            logger.error("get recovery task instance failed : " + e.getMessage(),e);
        }
        return null;
    }

    /**
     * get start task instance list
     * @param cmdParam command param
     * @return task instance list
     */
    private List<TaskInstance> getStartTaskInstanceList(String cmdParam){

        List<TaskInstance> instanceList = new ArrayList<>();
        Map<String, String> paramMap = JSONUtils.toMap(cmdParam);

        if(paramMap != null && paramMap.containsKey(CMDPARAM_RECOVERY_START_NODE_STRING)){
            String[] idList = paramMap.get(CMDPARAM_RECOVERY_START_NODE_STRING).split(Constants.COMMA);
            for(String nodeId : idList){
                TaskInstance task = getRecoveryTaskInstance(nodeId);
                if(task != null){
                    instanceList.add(task);
                }
            }
        }
        return instanceList;
    }

    /**
     * parse "StartNodeNameList" from cmd param
     * @param cmdParam command param
     * @return start node name list
     */
    private List<String> parseStartNodeName(String cmdParam){
        List<String> startNodeNameList = new ArrayList<>();
        Map<String, String> paramMap = JSONUtils.toMap(cmdParam);
        if(paramMap == null){
            return startNodeNameList;
        }
        if(paramMap.containsKey(CMDPARAM_START_NODE_NAMES)){
            startNodeNameList = Arrays.asList(paramMap.get(CMDPARAM_START_NODE_NAMES).split(Constants.COMMA));
        }
        return startNodeNameList;
    }

    /**
     * generate start node name list from parsing command param;
     * if "StartNodeIdList" exists in command param, return StartNodeIdList
     * @return recovery node name list
     */
    private List<String> getRecoveryNodeNameList(){
        List<String> recoveryNodeNameList = new ArrayList<>();
        if(recoverNodeIdList.size() > 0) {
            for (TaskInstance task : recoverNodeIdList) {
                recoveryNodeNameList.add(task.getName());
            }
        }
        return recoveryNodeNameList;
    }

    /**
     * generate flow dag
     * @param processDefinitionJson process definition json
     * @param startNodeNameList     start node name list
     * @param recoveryNodeNameList  recovery node name list
     * @param depNodeType           depend node type
     * @return ProcessDag           process dag
     * @throws Exception            exception
     */
    public ProcessDag generateFlowDag(String processDefinitionJson,
                                      List<String> startNodeNameList,
                                      List<String> recoveryNodeNameList,
                                      TaskDependType depNodeType)throws Exception{
        return DagHelper.generateFlowDag(processDefinitionJson, startNodeNameList, recoveryNodeNameList, depNodeType);
    }
}
