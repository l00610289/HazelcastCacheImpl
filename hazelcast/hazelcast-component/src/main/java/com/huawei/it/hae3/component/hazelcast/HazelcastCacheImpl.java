/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2012-2019. All rights reserved.
 */

package com.huawei.it.hae3.component.hazelcast;

import com.huawei.it.common.exception.ApplicationException;
import com.huawei.it.common.framework.service.ApplicationResource;
import com.huawei.it.common.framework.service.ICache;
import com.huawei.it.common.framework.service.util.CommonUtil;
import com.huawei.it.discovery.api.model.ParamContext;
import com.huawei.it.discovery.client.RouteClient;
import com.huawei.it.discovery.common.exception.ClientInvokingException;
import com.huawei.it.discovery.util.AddressUtils;
import com.huawei.it.discovery.vo.AddressBO;
import com.huawei.it.discovery.vo.AddressBizVO;
import com.huawei.it.hae3.component.spi.ServiceActive;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.map.IMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/**
 * hazelcast缓存
 *
 * @author l00610289
 * @since 2021-12-10
 */
@ServiceActive(name = "hazelcast")
public class HazelcastCacheImpl implements ICache {
    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastCacheImpl.class);

    private static final String AUTO_ADDRESS = "auto_address";
    private static final String FLAG = "false";
    private static final String MEMBERS = "members";
    private static final String HAZELCAST_CONFIG = "hazelcast_config";
    private static final String MAP_CONFIG = "map_config";
    private static final String HAE_HAZELCAST_INSTANCE = "Hae_hazelcast_instance";
    private static final String MAP_NAME = "name";
    private static final String BACKUP = "backup";
    private static final String ASYNC_BACKUP = "async_backup";
    private static final String TIME_TO_LIVE = "timeToLive";
    private static final String MAX_IDLE = "maxIdle";
    private static final Set<String> CACHE_NAME_SET = new HashSet<>();
    private static HazelcastInstance CACHE;

    public HazelcastCacheImpl() {
    }

    @Override
    public void init(Map map) throws ApplicationException {
        if (map == null || map.size() <= 0) {
            LOGGER.error("extended config is Empty!");
            return;
        }
        createCache(map);
    }

    @Override
    public void createCache(Map map) throws ApplicationException {
        Config config = new Config();
        Map hazelcastMap = (Map) map.get(HAZELCAST_CONFIG);
        if (map.get(AUTO_ADDRESS) != null && FLAG.equals(map.get(AUTO_ADDRESS))) {
            if (hazelcastMap == null || hazelcastMap.isEmpty() || hazelcastMap.get(MEMBERS) == null) {
                LOGGER.error("cache cluster members are needed!");
                return;
            }

            String members = (String) hazelcastMap.get(MEMBERS);
            String[] memberArr = members.split(";");
            List<String> ipList = new ArrayList<>();
            Collections.addAll(ipList, memberArr);
            NetworkConfig networkConfig = initNetworkConfigs(ipList);
            config.setNetworkConfig(networkConfig);
        } else {
            List ipMembers = getNodeAddress();
            NetworkConfig networkConfig = initNetworkConfigs(ipMembers);
            config.setNetworkConfig(networkConfig);
        }

        if (hazelcastMap != null && hazelcastMap.containsKey(MAP_CONFIG)) {
            Map configMap = initMapConfigs((Map) hazelcastMap.get(MAP_CONFIG));
            config.setMapConfigs(configMap);
        }

        CACHE = Hazelcast.newHazelcastInstance(config);
    }

    private static List getNodeAddress() throws ApplicationException {
        String subAppName = ApplicationResource.getAppConfigInfo().getSubAppName();
        String dockerRegion = ApplicationResource.getDockerInfo().getDockerRegion();
        String dockerEnv = ApplicationResource.getDockerInfo().getDockerEnv();
        String addressUrl = (String)ApplicationResource.publicConfigResource.get("address.endpoint.default");

        List<String> ipMembers = new ArrayList<>();
        List<AddressBO> dimensionAddressList = new ArrayList<AddressBO>();
        try {
            AddressBizVO addressBizVo =
                    AddressBizVO.builder().setServiceAlias(subAppName).setRegion(dockerRegion)
                            .setEnv(dockerEnv).setServiceVersion("1.0").setCallType("R")
                            .setClientAppAlias("").setCustomermize("{}").builder();
            ParamContext paramContext =
                    RouteClient.createParamContextBuilder().setAddressUrl(addressUrl).setAddressBizVo(addressBizVo).build();

            LOGGER.info("========>> paramContext is:{}",paramContext);
            dimensionAddressList = RouteClient.getRoute(paramContext);
            LOGGER.info("======dimensionAddressList is==>>>{}", dimensionAddressList);

        } catch (Exception exception) {
            exception.printStackTrace();
            throw new ApplicationException("getServiceURL error! ==>>>", exception);
        }

        if (dimensionAddressList == null) {
            throw new ApplicationException("====>>>> dimensionAddressList is null!");
        }
        for (AddressBO addressBO : dimensionAddressList) {
            String urlAddress = addressBO.getUrlAddress();
            int begin = urlAddress.indexOf("//");
            int end = urlAddress.lastIndexOf(":");
            String address = urlAddress.substring(begin + 2, end);
            ipMembers.add(address);
        }
        return ipMembers;
    }

    private static NetworkConfig initNetworkConfigs(List ipList) {
        NetworkConfig networkConfig = new NetworkConfig();
        List<String> members = new ArrayList<>(ipList);
        try {
            TcpIpConfig tcpIpConfig = new TcpIpConfig();
            tcpIpConfig.setEnabled(true);
            tcpIpConfig.setMembers(members);

            JoinConfig joinConfig = new JoinConfig();

            joinConfig.setTcpIpConfig(tcpIpConfig);
            networkConfig.setJoin(joinConfig);
        } catch (Exception exception) {
            LOGGER.error("======hazelcast TCP/IP delivery mode error======!!!");
        }
        return networkConfig;
    }

    private static Map initMapConfigs(Map initMap) {
        MapConfig mapConfig = new MapConfig();
        String mapName = initMap.get(MAP_NAME) == null ? HAE_HAZELCAST_INSTANCE : (String) initMap.get("name");

        if (initMap.containsKey(BACKUP)) {
            int backup = CommonUtil.getIntValueByKey(initMap, BACKUP);
            mapConfig.setBackupCount(backup);
        }
        if (initMap.containsKey(ASYNC_BACKUP)) {
            int asyncBackup = CommonUtil.getIntValueByKey(initMap, ASYNC_BACKUP);
            mapConfig.setAsyncBackupCount(asyncBackup);
        }

        if (initMap.containsKey(TIME_TO_LIVE)) {
            int timeToLive = CommonUtil.getIntValueByKey(initMap, TIME_TO_LIVE);
            mapConfig.setTimeToLiveSeconds(timeToLive);
        }

        if (initMap.containsKey(MAX_IDLE)) {
            int maxIdle = CommonUtil.getIntValueByKey(initMap, MAX_IDLE);
            mapConfig.setMaxIdleSeconds(maxIdle);
        }

        Map<String, MapConfig> configHashMap = new HashMap<>();
        configHashMap.put(mapName, mapConfig);
        return configHashMap;
    }

    @Override
    public boolean put(String key, Object value) {
        return put(HAE_HAZELCAST_INSTANCE, key, value);
    }

    @Override
    public boolean put(String cacheName, String key, Object value) {
        if (!cacheName.isEmpty()) {
            CACHE.getMap(cacheName).put(key, value);
            CACHE_NAME_SET.add(cacheName);
            return true;
        }
        return false;
    }

    @Override
    public boolean put(String cacheName, String key, Object value, int expireTime) {
        if (cacheName != null && key != null && value != null) {
            CACHE.getMap(cacheName).put(key, value, expireTime, TimeUnit.SECONDS);
            CACHE_NAME_SET.add(cacheName);
            return true;
        }
        return false;
    }

    @Override
    public boolean put(String cacheName, String key, Object value, int expireTime, int idleTime) {
        if (cacheName != null && key != null && value != null) {
            CACHE.getMap(cacheName).put(key, value, expireTime, TimeUnit.SECONDS, idleTime, TimeUnit.SECONDS);
            CACHE_NAME_SET.add(cacheName);
            return true;
        }
        return false;
    }

    @Override
    public Object get(String key) {
        return get(HAE_HAZELCAST_INSTANCE, key);
    }

    @Override
    public Object get(String cacheName, String key) {
        if (CACHE.getMap(cacheName).containsKey(key)) {
            return CACHE.getMap(cacheName).get(key);
        }
        return null;
    }

    @Override
    public String[] getCacheNames() {
        return CACHE_NAME_SET.toArray(new String[0]);
    }


    @Override
    public boolean remove(String key) {
        return remove(HAE_HAZELCAST_INSTANCE, key);
    }

    @Override
    public boolean remove(String cacheName, String key) {
        IMap<Object, Object> map = CACHE.getMap(cacheName);
        if (map.containsKey(key)) {
            map.remove(key);
            if (map.isEmpty()) {
                CACHE_NAME_SET.remove(cacheName);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean clear(String cacheName) {
        IMap<Object, Object> map = CACHE.getMap(cacheName);
        if (!map.isEmpty()) {
            map.clear();
            return true;
        }
        return false;
    }

    @Override
    public boolean clearKeyWithPrefix(String keyPrefix) {
        return clearKeyWithPrefix(HAE_HAZELCAST_INSTANCE, keyPrefix);
    }

    @Override
    public boolean clearKeyWithPrefix(String cacheName, String keyPrefix) {
        if (!CACHE.getMap(cacheName).isEmpty()) {
            IMap<String, Object> map = CACHE.getMap(cacheName);
            Iterator<Map.Entry<String, Object>> iterator = map.iterator();

            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                if (entry != null && entry.getKey() != null && entry.getKey().startsWith(keyPrefix)) {
                    iterator.remove();
                }
            }

            if (map.isEmpty()) {
                CACHE_NAME_SET.remove(cacheName);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean clear() {
        Iterator<String> iterator = CACHE_NAME_SET.iterator();
        while (iterator.hasNext()){
            String cacheName = iterator.next();
            if (!clear(cacheName)){
                return false;
            }
            iterator.remove();
        }
        return true;
    }

    @Override
    public void destroy() {
        try {
            if (null != CACHE) {
                CACHE.shutdown();
            }
        } catch (Throwable e) {
            LOGGER.error("cache shutdown failed,reason:", e);
        }
    }

    @Override
    public boolean isAvailableCacheService() {
        return true;
    }
}
