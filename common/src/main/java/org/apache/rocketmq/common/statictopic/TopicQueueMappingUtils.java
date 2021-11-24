/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.common.statictopic;

import com.google.common.collect.ImmutableList;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.TopicConfig;

import java.io.File;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;

public class TopicQueueMappingUtils {

    public static class MappingAllocator {
        Map<String, Integer> brokerNumMap = new HashMap<String, Integer>();
        Map<Integer, String> idToBroker = new HashMap<Integer, String>();
        int currentIndex = 0;
        Random random = new Random();
        List<String> leastBrokers = new ArrayList<String>();
        private MappingAllocator(Map<Integer, String> idToBroker, Map<String, Integer> brokerNumMap) {
            this.idToBroker.putAll(idToBroker);
            this.brokerNumMap.putAll(brokerNumMap);
        }

        private void freshState() {
            int minNum = -1;
            for (Map.Entry<String, Integer> entry : brokerNumMap.entrySet()) {
                if (entry.getValue() > minNum) {
                    leastBrokers.clear();
                    leastBrokers.add(entry.getKey());
                } else if (entry.getValue() == minNum) {
                    leastBrokers.add(entry.getKey());
                }
            }
            currentIndex = random.nextInt(leastBrokers.size());
        }
        private String nextBroker() {
            if (leastBrokers.isEmpty()) {
                freshState();
            }
            int tmpIndex = currentIndex % leastBrokers.size();
            return leastBrokers.remove(tmpIndex);
        }

        public Map<String, Integer> getBrokerNumMap() {
            return brokerNumMap;
        }

        public void upToNum(int maxQueueNum) {
            int currSize = idToBroker.size();
            if (maxQueueNum <= currSize) {
                return;
            }
            for (int i = currSize; i < maxQueueNum; i++) {
                String nextBroker = nextBroker();
                if (brokerNumMap.containsKey(nextBroker)) {
                    brokerNumMap.put(nextBroker, brokerNumMap.get(nextBroker) + 1);
                } else {
                    brokerNumMap.put(nextBroker, 1);
                }
                idToBroker.put(i, nextBroker);
            }
        }

        public Map<Integer, String> getIdToBroker() {
            return idToBroker;
        }
    }

    public static MappingAllocator buildMappingAllocator(Map<Integer, String> idToBroker, Map<String, Integer> brokerNumMap) {
        return new MappingAllocator(idToBroker, brokerNumMap);
    }

    public static Map.Entry<Long, Integer> findMaxEpochAndQueueNum(List<TopicQueueMappingDetail> mappingDetailList) {
        long epoch = -1;
        int queueNum = 0;
        for (TopicQueueMappingDetail mappingDetail : mappingDetailList) {
            if (mappingDetail.getEpoch() > epoch) {
                epoch = mappingDetail.getEpoch();
            }
            if (mappingDetail.getTotalQueues() > queueNum) {
                queueNum = mappingDetail.getTotalQueues();
            }
        }
        return new AbstractMap.SimpleImmutableEntry<Long, Integer>(epoch, queueNum);
    }

    public static List<TopicQueueMappingDetail> getMappingDetailFromConfig(Collection<TopicConfigAndQueueMapping> configs) {
        List<TopicQueueMappingDetail> detailList = new ArrayList<TopicQueueMappingDetail>();
        for (TopicConfigAndQueueMapping configMapping : configs) {
            if (configMapping.getMappingDetail() != null) {
                detailList.add(configMapping.getMappingDetail());
            }
        }
        return detailList;
    }

    public static Map.Entry<Long, Integer> checkConsistenceOfTopicConfigAndQueueMapping(String topic, Map<String, TopicConfigAndQueueMapping> brokerConfigMap) {
        if (brokerConfigMap == null
            || brokerConfigMap.isEmpty()) {
            return null;
        }
        //make sure it it not null
        long maxEpoch = -1;
        int maxNum = -1;
        for (Map.Entry<String, TopicConfigAndQueueMapping> entry : brokerConfigMap.entrySet()) {
            String broker = entry.getKey();
            TopicConfigAndQueueMapping configMapping = entry.getValue();
            if (configMapping.getMappingDetail() == null) {
                throw new RuntimeException("Mapping info should not be null in broker " + broker);
            }
            TopicQueueMappingDetail mappingDetail = configMapping.getMappingDetail();
            if (!broker.equals(mappingDetail.getBname())) {
                throw new RuntimeException(String.format("The broker name is not equal %s != %s ", broker, mappingDetail.getBname()));
            }
            if (mappingDetail.isDirty()) {
                throw new RuntimeException("The mapping info is dirty in broker  " + broker);
            }
            if (!configMapping.getTopicName().equals(mappingDetail.getTopic())) {
                throw new RuntimeException("The topic name is inconsistent in broker  " + broker);
            }
            if (topic != null
                && !topic.equals(mappingDetail.getTopic())) {
                throw new RuntimeException("The topic name is not match for broker  " + broker);
            }

            if (maxEpoch != -1
                && maxEpoch != mappingDetail.getEpoch()) {
                throw new RuntimeException(String.format("epoch dose not match %d != %d in %s", maxEpoch, mappingDetail.getEpoch(), mappingDetail.getBname()));
            } else {
                maxEpoch = mappingDetail.getEpoch();
            }

            if (maxNum != -1
                && maxNum != mappingDetail.getTotalQueues()) {
                throw new RuntimeException(String.format("total queue number dose not match %d != %d in %s", maxNum, mappingDetail.getTotalQueues(), mappingDetail.getBname()));
            } else {
                maxNum = mappingDetail.getTotalQueues();
            }
        }
        return new AbstractMap.SimpleEntry<Long, Integer>(maxEpoch, maxNum);
    }

    public static void makeSureLogicQueueMappingItemImmutable(List<LogicQueueMappingItem> oldItems, List<LogicQueueMappingItem> newItems) {
        if (oldItems == null || oldItems.isEmpty()) {
            return;
        }
        if (newItems == null || newItems.isEmpty() || newItems.size() < oldItems.size()) {
            throw new RuntimeException("The new item list is smaller than old ones");
        }
        int iold = 0, inew = 0;
        while (iold < oldItems.size() && inew < newItems.size()) {
            LogicQueueMappingItem newItem = newItems.get(inew);
            LogicQueueMappingItem oldItem = oldItems.get(iold);
            if (newItem.getGen() < oldItem.getGen()) {
                inew++;
                continue;
            } else if (oldItem.getGen() < newItem.getGen()){
                throw new RuntimeException("The gen is not correct for old item");
            } else {
                assert oldItem.getBname().equals(newItem.getBname());
                assert oldItem.getQueueId() == newItem.getQueueId();
                assert oldItem.getStartOffset() == newItem.getStartOffset();
                if (oldItem.getLogicOffset() != -1) {
                    assert oldItem.getLogicOffset() == newItem.getLogicOffset();
                }
                iold++;
                inew++;
            }
        }
    }


    public static void checkLogicQueueMappingItemOffset(List<LogicQueueMappingItem> items) {
        if (items == null
            || items.isEmpty()) {
            return;
        }
        int lastGen = -1;
        long lastOffset = -1;
        for (int i = items.size() - 1; i >=0 ; i--) {
            LogicQueueMappingItem item = items.get(i);
            if (item.getStartOffset() < 0
                    || item.getGen() < 0
                    || item.getQueueId() < 0) {
                throw new RuntimeException("The field is illegal, should not be negative");
            }
            if (lastGen != -1 && item.getGen() >= lastGen) {
                throw new RuntimeException("The gen dose not increase monotonically");
            }

            if (item.getEndOffset() != -1
                && item.getEndOffset() < item.getStartOffset()) {
                throw new RuntimeException("The endOffset is smaller than the start offset");
            }

            if (lastOffset != -1 && item.getLogicOffset() != -1) {
                if (item.getLogicOffset() >= lastOffset) {
                    throw new RuntimeException("The base logic offset dose not increase monotonically");
                }
                if (item.computeMaxStaticQueueOffset() >= lastOffset) {
                    throw new RuntimeException("The max logic offset dose not increase monotonically");
                }
            }
            lastGen = item.getGen();
            lastOffset = item.getLogicOffset();
        }
    }

    public static Map<Integer, TopicQueueMappingOne> checkAndBuildMappingItems(List<TopicQueueMappingDetail> mappingDetailList, boolean replace, boolean checkConsistence) {
        Collections.sort(mappingDetailList, new Comparator<TopicQueueMappingDetail>() {
            @Override
            public int compare(TopicQueueMappingDetail o1, TopicQueueMappingDetail o2) {
                return (int)(o2.getEpoch() - o1.getEpoch());
            }
        });

        int maxNum = 0;
        Map<Integer, TopicQueueMappingOne> globalIdMap = new HashMap<Integer, TopicQueueMappingOne>();
        for (TopicQueueMappingDetail mappingDetail : mappingDetailList) {
            if (mappingDetail.totalQueues > maxNum) {
                maxNum = mappingDetail.totalQueues;
            }
            for (Map.Entry<Integer, List<LogicQueueMappingItem>>  entry : mappingDetail.getHostedQueues().entrySet()) {
                Integer globalid = entry.getKey();
                checkLogicQueueMappingItemOffset(entry.getValue());
                String leaderBrokerName  = getLeaderBroker(entry.getValue());
                if (!leaderBrokerName.equals(mappingDetail.getBname())) {
                    //not the leader
                    continue;
                }
                if (globalIdMap.containsKey(globalid)) {
                    if (!replace) {
                        throw new RuntimeException(String.format("The queue id is duplicated in broker %s %s", leaderBrokerName, mappingDetail.getBname()));
                    }
                } else {
                    globalIdMap.put(globalid, new TopicQueueMappingOne(mappingDetail.topic, mappingDetail.bname, globalid, entry.getValue()));
                }
            }
        }
        if (checkConsistence) {
            if (maxNum != globalIdMap.size()) {
                throw new RuntimeException(String.format("The total queue number in config dose not match the real hosted queues %d != %d", maxNum, globalIdMap.size()));
            }
            for (int i = 0; i < maxNum; i++) {
                if (!globalIdMap.containsKey(i)) {
                    throw new RuntimeException(String.format("The queue number %s is not in globalIdMap", i));
                }
            }
        }
        return globalIdMap;
    }

    public static String getLeaderBroker(List<LogicQueueMappingItem> items) {
        return getLeaderItem(items).getBname();
    }
    public static LogicQueueMappingItem getLeaderItem(List<LogicQueueMappingItem> items) {
        assert items.size() > 0;
        return items.get(items.size() - 1);
    }

    public static String writeToTemp(TopicRemappingDetailWrapper wrapper, boolean after) {
        String topic = wrapper.getTopic();
        String data = wrapper.toJson();
        String suffix = TopicRemappingDetailWrapper.SUFFIX_BEFORE;
        if (after) {
            suffix = TopicRemappingDetailWrapper.SUFFIX_AFTER;
        }
        String fileName = System.getProperty("java.io.tmpdir") + File.separator + topic + "-" + wrapper.getEpoch() + suffix;
        try {
            MixAll.string2File(data, fileName);
            return fileName;
        } catch (Exception e) {
            throw new RuntimeException("write file failed " + fileName,e);
        }
    }

    public static long blockSeqRoundUp(long offset, long blockSeqSize) {
        long num = offset/blockSeqSize;
        long left = offset % blockSeqSize;
        if (left < blockSeqSize/2) {
            return (num + 1) * blockSeqSize;
        } else {
            return (num + 2) * blockSeqSize;
        }
    }

    public static TopicRemappingDetailWrapper createTopicConfigMapping(String topic, int queueNum, Set<String> targetBrokers, Map<String, TopicConfigAndQueueMapping> brokerConfigMap) {
        Map<Integer, TopicQueueMappingOne> globalIdMap = new HashMap<Integer, TopicQueueMappingOne>();
        Map.Entry<Long, Integer> maxEpochAndNum = new AbstractMap.SimpleImmutableEntry<Long, Integer>(System.currentTimeMillis(), queueNum);
        if (!brokerConfigMap.isEmpty()) {
            maxEpochAndNum = TopicQueueMappingUtils.checkConsistenceOfTopicConfigAndQueueMapping(topic, brokerConfigMap);
            globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<TopicQueueMappingDetail>(TopicQueueMappingUtils.getMappingDetailFromConfig(brokerConfigMap.values())), false, true);
        }
        if (queueNum < globalIdMap.size()) {
            throw new RuntimeException(String.format("Cannot decrease the queue num for static topic %d < %d", queueNum, globalIdMap.size()));
        }
        //check the queue number
        if (queueNum == globalIdMap.size()) {
            throw new RuntimeException("The topic queue num is equal the existed queue num, do nothing");
        }

        //the check is ok, now do the mapping allocation
        Map<String, Integer> brokerNumMap = new HashMap<String, Integer>();
        for (String broker: targetBrokers) {
            brokerNumMap.put(broker, 0);
        }
        final Map<Integer, String> oldIdToBroker = new HashMap<Integer, String>();
        for (Map.Entry<Integer, TopicQueueMappingOne> entry : globalIdMap.entrySet()) {
            String leaderbroker = entry.getValue().getBname();
            oldIdToBroker.put(entry.getKey(), leaderbroker);
            if (!brokerNumMap.containsKey(leaderbroker)) {
                brokerNumMap.put(leaderbroker, 1);
            } else {
                brokerNumMap.put(leaderbroker, brokerNumMap.get(leaderbroker) + 1);
            }
        }
        TopicQueueMappingUtils.MappingAllocator allocator = TopicQueueMappingUtils.buildMappingAllocator(oldIdToBroker, brokerNumMap);
        allocator.upToNum(queueNum);
        Map<Integer, String> newIdToBroker = allocator.getIdToBroker();

        //construct the topic configAndMapping
        long newEpoch = Math.max(maxEpochAndNum.getKey() + 1000, System.currentTimeMillis());
        for (Map.Entry<Integer, String> e : newIdToBroker.entrySet()) {
            Integer queueId = e.getKey();
            String broker = e.getValue();
            if (globalIdMap.containsKey(queueId)) {
                //ignore the exited
                continue;
            }
            TopicConfigAndQueueMapping configMapping;
            if (!brokerConfigMap.containsKey(broker)) {
                configMapping = new TopicConfigAndQueueMapping(new TopicConfig(topic), new TopicQueueMappingDetail(topic, 0, broker, -1));
                configMapping.setWriteQueueNums(1);
                configMapping.setReadQueueNums(1);
                brokerConfigMap.put(broker, configMapping);
            } else {
                configMapping = brokerConfigMap.get(broker);
                configMapping.setWriteQueueNums(configMapping.getWriteQueueNums() + 1);
                configMapping.setReadQueueNums(configMapping.getReadQueueNums() + 1);
            }
            LogicQueueMappingItem mappingItem = new LogicQueueMappingItem(0, configMapping.getWriteQueueNums() - 1, broker, 0, 0, -1, -1, -1);
            TopicQueueMappingDetail.putMappingInfo(configMapping.getMappingDetail(), queueId, ImmutableList.of(mappingItem));
        }

        // set the topic config
        for (Map.Entry<String, TopicConfigAndQueueMapping> entry : brokerConfigMap.entrySet()) {
            TopicConfigAndQueueMapping configMapping = entry.getValue();
            configMapping.getMappingDetail().setEpoch(newEpoch);
            configMapping.getMappingDetail().setTotalQueues(queueNum);
        }
        //double check the config
        TopicQueueMappingUtils.checkConsistenceOfTopicConfigAndQueueMapping(topic, brokerConfigMap);
        TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<TopicQueueMappingDetail>(TopicQueueMappingUtils.getMappingDetailFromConfig(brokerConfigMap.values())), false, true);

        return new TopicRemappingDetailWrapper(topic, TopicRemappingDetailWrapper.TYPE_CREATE_OR_UPDATE, newEpoch, brokerConfigMap, new HashSet<String>(), new HashSet<String>());
    }


    public static TopicRemappingDetailWrapper remappingStaticTopic(String topic, Map<String, TopicConfigAndQueueMapping> brokerConfigMap, Set<String> targetBrokers) {
        final Map.Entry<Long, Integer> maxEpochAndNum = TopicQueueMappingUtils.checkConsistenceOfTopicConfigAndQueueMapping(topic, brokerConfigMap);
        final Map<Integer, TopicQueueMappingOne> globalIdMap = TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<TopicQueueMappingDetail>(TopicQueueMappingUtils.getMappingDetailFromConfig(brokerConfigMap.values())), false, true);
        //the check is ok, now do the mapping allocation
        int maxNum = maxEpochAndNum.getValue();

        Map<String, Integer> brokerNumMap = new HashMap<String, Integer>();
        for (String broker: targetBrokers) {
            brokerNumMap.put(broker, 0);
        }

        TopicQueueMappingUtils.MappingAllocator allocator = TopicQueueMappingUtils.buildMappingAllocator(new HashMap<Integer, String>(), brokerNumMap);
        allocator.upToNum(maxNum);
        Map<String, Integer> expectedBrokerNumMap = allocator.getBrokerNumMap();
        Queue<Integer> waitAssignQueues = new ArrayDeque<Integer>();
        Map<Integer, String> expectedIdToBroker = new HashMap<Integer, String>();
        //the following logic will make sure that, for one broker, either "map in" or "map out"
        //It can't both,  map in some queues but also map out some queues.
        for (Map.Entry<Integer, TopicQueueMappingOne> entry : globalIdMap.entrySet()) {
            Integer queueId = entry.getKey();
            TopicQueueMappingOne mappingOne = entry.getValue();
            String leaderBroker = mappingOne.getBname();
            if (expectedBrokerNumMap.containsKey(leaderBroker)) {
                if (expectedBrokerNumMap.get(leaderBroker) > 0) {
                    expectedIdToBroker.put(queueId, leaderBroker);
                    expectedBrokerNumMap.put(leaderBroker, expectedBrokerNumMap.get(leaderBroker) - 1);
                } else {
                    waitAssignQueues.add(queueId);
                    expectedBrokerNumMap.remove(leaderBroker);
                }
            } else {
                waitAssignQueues.add(queueId);
            }
        }

        for (Map.Entry<String, Integer> entry: expectedBrokerNumMap.entrySet()) {
            String broker = entry.getKey();
            Integer queueNum = entry.getValue();
            for (int i = 0; i < queueNum; i++) {
                Integer queueId = waitAssignQueues.poll();
                assert queueId != null;
                expectedIdToBroker.put(queueId, broker);
            }
        }
        long newEpoch = Math.max(maxEpochAndNum.getKey() + 1000, System.currentTimeMillis());

        //Now construct the remapping info
        Set<String> brokersToMapOut = new HashSet<String>();
        Set<String> brokersToMapIn = new HashSet<String>();
        for (Map.Entry<Integer, String> mapEntry : expectedIdToBroker.entrySet()) {
            Integer queueId = mapEntry.getKey();
            String broker = mapEntry.getValue();
            TopicQueueMappingOne topicQueueMappingOne = globalIdMap.get(queueId);
            assert topicQueueMappingOne != null;
            if (topicQueueMappingOne.getBname().equals(broker)) {
                continue;
            }
            //remapping
            final String mapInBroker = broker;
            final String mapOutBroker = topicQueueMappingOne.getBname();
            brokersToMapIn.add(mapInBroker);
            brokersToMapOut.add(mapOutBroker);
            TopicConfigAndQueueMapping mapInConfig = brokerConfigMap.get(mapInBroker);
            TopicConfigAndQueueMapping mapOutConfig = brokerConfigMap.get(mapOutBroker);

            mapInConfig.setWriteQueueNums(mapInConfig.getWriteQueueNums() + 1);
            mapInConfig.setWriteQueueNums(mapInConfig.getWriteQueueNums() + 1);

            List<LogicQueueMappingItem> items = new ArrayList<LogicQueueMappingItem>(topicQueueMappingOne.getItems());
            LogicQueueMappingItem last = items.get(items.size() - 1);
            items.add(new LogicQueueMappingItem(last.getGen() + 1, mapInConfig.getWriteQueueNums() - 1, mapInBroker, -1, 0, -1, -1, -1));

            ImmutableList<LogicQueueMappingItem> resultItems = ImmutableList.copyOf(items);
            //Use the same object
            TopicQueueMappingDetail.putMappingInfo(mapInConfig.getMappingDetail(), queueId, resultItems);
            TopicQueueMappingDetail.putMappingInfo(mapOutConfig.getMappingDetail(), queueId, resultItems);
        }

        for (Map.Entry<String, TopicConfigAndQueueMapping> entry : brokerConfigMap.entrySet()) {
            TopicConfigAndQueueMapping configMapping = entry.getValue();
            configMapping.getMappingDetail().setEpoch(newEpoch);
            configMapping.getMappingDetail().setTotalQueues(maxNum);
        }

        //double check
        TopicQueueMappingUtils.checkConsistenceOfTopicConfigAndQueueMapping(topic, brokerConfigMap);
        TopicQueueMappingUtils.checkAndBuildMappingItems(new ArrayList<TopicQueueMappingDetail>(TopicQueueMappingUtils.getMappingDetailFromConfig(brokerConfigMap.values())), false, true);

        return new TopicRemappingDetailWrapper(topic, TopicRemappingDetailWrapper.TYPE_REMAPPING, newEpoch, brokerConfigMap, brokersToMapIn, brokersToMapOut);
    }

}