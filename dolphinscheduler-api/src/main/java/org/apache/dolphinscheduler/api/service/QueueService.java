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
package org.apache.dolphinscheduler.api.service;

import org.apache.dolphinscheduler.api.enums.Status;
import org.apache.dolphinscheduler.api.utils.PageInfo;
import org.apache.dolphinscheduler.api.utils.Result;
import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.dao.entity.Queue;
import org.apache.dolphinscheduler.dao.entity.User;
import org.apache.dolphinscheduler.dao.mapper.QueueMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * queue service
 */
@Service
public class QueueService extends BaseService {

    private static final Logger logger = LoggerFactory.getLogger(TenantService.class);

    @Autowired
    private QueueMapper queueMapper;

    /**
     * query queue list
     *
     * @param loginUser login user
     * @return queue list
     */
    public Map<String, Object> queryList(User loginUser) {
        Map<String, Object> result = new HashMap<>(5);
        if (checkAdmin(loginUser, result)) {
            return result;
        }

        List<Queue> queueList = queueMapper.selectList(null);
        result.put(Constants.DATA_LIST, queueList);
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * query queue list paging
     *
     * @param loginUser login user
     * @param pageNo page number
     * @param searchVal search value
     * @param pageSize page size
     * @return queue list
     */
    public Map<String, Object> queryList(User loginUser, String searchVal, Integer pageNo, Integer pageSize) {
        Map<String, Object> result = new HashMap<>(5);
        if (checkAdmin(loginUser, result)) {
            return result;
        }

        Page<Queue> page = new Page(pageNo, pageSize);


        IPage<Queue> queueList = queueMapper.queryQueuePaging(page, searchVal);

        Integer count = (int)queueList.getTotal();
        PageInfo<Queue> pageInfo = new PageInfo<>(pageNo, pageSize);
        pageInfo.setTotalCount(count);
        pageInfo.setLists(queueList.getRecords());
        result.put(Constants.DATA_LIST, pageInfo);
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * create queue
     *
     * @param loginUser login user
     * @param queue queue
     * @param queueName queue name
     * @return create result
     */
    public Map<String, Object> createQueue(User loginUser, String queue, String queueName) {
        Map<String, Object> result = new HashMap<>(5);
        if (checkAdmin(loginUser, result)) {
            return result;
        }

        if(StringUtils.isEmpty(queue)){
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR, queue);
            return result;
        }

        if(StringUtils.isEmpty(queueName)){
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR, queueName);
            return result;
        }

        if (checkQueueNameExist(queueName)) {
            putMsg(result, Status.QUEUE_NAME_EXIST, queueName);
            return result;
        }

        if (checkQueueExist(queue)) {
            putMsg(result, Status.QUEUE_VALUE_EXIST, queue);
            return result;
        }

        Queue queueObj = new Queue();
        Date now = new Date();

        queueObj.setQueue(queue);
        queueObj.setQueueName(queueName);
        queueObj.setCreateTime(now);
        queueObj.setUpdateTime(now);

        queueMapper.insert(queueObj);
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * update queue
     *
     * @param loginUser login user
     * @param queue queue
     * @param id queue id
     * @param queueName queue name
     * @return update result code
     */
    public Map<String, Object> updateQueue(User loginUser, int id, String queue, String queueName) {
        Map<String, Object> result = new HashMap<>(5);
        if (checkAdmin(loginUser, result)) {
            return result;
        }

        Queue queueObj = queueMapper.selectById(id);
        if (queueObj == null) {
            putMsg(result, Status.QUEUE_NOT_EXIST, id);
            return result;
        }

        // whether queue value or queueName is changed
        if (queue.equals(queueObj.getQueue()) && queueName.equals(queueObj.getQueueName())) {
            putMsg(result, Status.NEED_NOT_UPDATE_QUEUE);
            return result;
        }

        // check queue name is exist
        if (!queueName.equals(queueObj.getQueueName())) {
            if(checkQueueNameExist(queueName)){
                putMsg(result, Status.QUEUE_NAME_EXIST, queueName);
                return result;
            }
        }

        // check queue value is exist
        if (!queue.equals(queueObj.getQueue())) {
            if(checkQueueExist(queue)){
                putMsg(result, Status.QUEUE_VALUE_EXIST, queue);
                return result;
            }
        }

        // update queue
        Date now = new Date();
        queueObj.setQueue(queue);
        queueObj.setQueueName(queueName);
        queueObj.setUpdateTime(now);

        queueMapper.updateById(queueObj);
        putMsg(result, Status.SUCCESS);

        return result;
    }

    /**
     * verify queue and queueName
     *
     * @param queue queue
     * @param queueName queue name
     * @return true if the queue name not exists, otherwise return false
     */
    public Result verifyQueue(String queue, String queueName) {
        Result result=new Result();

        if (StringUtils.isEmpty(queue)) {
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR, queue);
            return result;
        }

        if (StringUtils.isEmpty(queueName)) {
            putMsg(result, Status.REQUEST_PARAMS_NOT_VALID_ERROR, queueName);
            return result;
        }


        if(checkQueueNameExist(queueName)){
            logger.error("queue name {} has exist, can't create again.", queueName);
            putMsg(result, Status.QUEUE_NAME_EXIST, queueName);
            return result;
        }

        if(checkQueueExist(queue)){
            logger.error("queue value {} has exist, can't create again.", queue);
            putMsg(result, Status.QUEUE_VALUE_EXIST, queue);
            return result;
        }

        putMsg(result, Status.SUCCESS);
        return result;
    }

    /**
     * check queue exist
     * if exists return true???not exists return false
     * check queue exist
     * @param queue queue
     * @return true if the queue not exists, otherwise return false
     */
    private boolean checkQueueExist(String queue) {
        return queueMapper.queryAllQueueList(queue, null).size()>0 ? true : false;
    }

    /**
     * check queue name exist
     * if exists return true???not exists return false
     * @param queueName queue name
     * @return true if the queue name not exists, otherwise return false
     */
    private boolean checkQueueNameExist(String queueName) {
        return queueMapper.queryAllQueueList(null ,queueName).size() > 0 ? true : false;
    }

}
