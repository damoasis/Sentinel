/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.dashboard.rule.nacos;

import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.ApiDefinitionEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.gateway.GatewayFlowRuleEntity;
import com.alibaba.csp.sentinel.dashboard.datasource.entity.rule.*;
import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.config.ConfigFactory;
import com.alibaba.nacos.api.config.ConfigService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * @author Eric Zhao
 * @since 1.4.0
 * 1） 注入Convert转换器，将FlowRuleEntity转化成FlowRule，以及反向转化
 * <p>
 * 2） 注入Nacos配置服务ConfigService
 */
@EnableConfigurationProperties(NacosPropertiesConfiguration.class)
@Configuration
public class NacosConfig {

    @Bean
    public Converter<List<FlowRuleEntity>, String> flowRuleEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<FlowRuleEntity>> flowRuleEntityDecoder() {
        return s -> JSON.parseArray(s, FlowRuleEntity.class);
    }

    @Bean
    public Converter<List<GatewayFlowRuleEntity>, String> gatewayFlowRuleEntityEncoder() {
        return list -> {
            JSONArray result = new JSONArray();
            if (list != null) {
                for (GatewayFlowRuleEntity entity : list) {
                    JSONObject json = (JSONObject) JSON.toJSON(entity);
                    json.put("intervalSec", GatewayFlowRuleEntity.calIntervalSec(entity.getInterval(), entity.getIntervalUnit()));
                    result.add(json);
                }
            }
            return result.toJSONString();
        };
    }

    @Bean
    public Converter<String, List<GatewayFlowRuleEntity>> gatewayFlowRuleEntityDecoder() {
        return s -> JSON.parseArray(s, GatewayFlowRuleEntity.class);
    }

    @Bean
    public Converter<List<DegradeRuleEntity>, String> degradeEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<DegradeRuleEntity>> degradeRuleEntityDecoder() {
        return s -> JSON.parseArray(s, DegradeRuleEntity.class);
    }

    @Bean
    public Converter<List<ParamFlowRuleEntity>, String> paramFlowRuleEntityEncoder() {
        // 将外层 Entity 元数据作为 rule 的 _metadata 扩展字段
        // 客户端解析时会自动忽略 _metadata，不影响规则生效
        // Dashboard 读取时可以从 _metadata 还原完整的 Entity 信息
        return list -> {
            JSONArray result = new com.alibaba.fastjson.JSONArray();

            for (ParamFlowRuleEntity entity : list) {
                // 1. 将 rule 转为 JSONObject
                JSONObject ruleJson = (com.alibaba.fastjson.JSONObject)
                        JSON.toJSON(entity.toRule());

                // 2. 将整个 entity 转为 JSON，提取非 rule 字段作为 metadata
                JSONObject entityJson = (com.alibaba.fastjson.JSONObject)
                        JSON.toJSON(entity);

                // 3. 移除 rule 字段，剩余的就是元数据
                entityJson.remove("rule");

                // 4. 将元数据添加到 rule 中
                if (!entityJson.isEmpty()) {
                    ruleJson.put("_metadata", entityJson);
                }

                result.add(ruleJson);
            }

            return result.toJSONString();
        };
    }

    @Bean
    public Converter<String, List<ParamFlowRuleEntity>> paramFlowRuleEntityDecoder() {
        // 解析时兼容多种格式：
        // 1. 扩展格式：[{rule字段 + "_metadata":{...}}] - 优先使用，还原完整 Entity
        // 2. Dashboard 旧格式：[{"app":"xxx", "rule":{...}}] - ParamFlowRuleEntity
        // 3. 纯规则格式：[{"resource":"xxx", "count":10, ...}] - ParamFlowRule
        return s -> {
            if (s == null || s.trim().isEmpty()) {
                return null;
            }

            try {
                JSONArray array = JSON.parseArray(s);
                List<ParamFlowRuleEntity> entities = new java.util.ArrayList<>();

                for (int i = 0; i < array.size(); i++) {
                    JSONObject obj = array.getJSONObject(i);

                    // 尝试提取 _metadata
                    JSONObject metadata = obj.getJSONObject("_metadata");

                    if (metadata != null) {
                        // 有 metadata，从扩展格式还原
                        obj.remove("_metadata");
                        metadata.put("rule", obj);

                        ParamFlowRuleEntity entity = metadata.toJavaObject(ParamFlowRuleEntity.class);

                        entities.add(entity);
                    } else if (obj.containsKey("rule")) {
                        // Dashboard 旧格式：有 rule 字段
                        entities.add(obj.toJavaObject(ParamFlowRuleEntity.class));
                    } else {
                        // 纯规则格式
                        com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule rule =
                                obj.toJavaObject(com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule.class);
                        entities.add(new ParamFlowRuleEntity(rule));
                    }
                }

                return entities;
            } catch (Exception e) {
                // 降级：尝试直接解析为 ParamFlowRule 数组
                throw new RuntimeException(e);
            }
        };
    }

    @Bean
    public Converter<List<AuthorityRuleEntity>, String> authRuleEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<AuthorityRuleEntity>> authRuleEntityDecoder() {
        return s -> JSON.parseArray(s, AuthorityRuleEntity.class);
    }

    @Bean
    public Converter<List<SystemRuleEntity>, String> systemRuleEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<SystemRuleEntity>> systemRuleEntityDecoder() {
        return s -> JSON.parseArray(s, SystemRuleEntity.class);
    }

    @Bean
    public Converter<List<ApiDefinitionEntity>, String> apiDefinitionEntityEncoder() {
        return JSON::toJSONString;
    }

    @Bean
    public Converter<String, List<ApiDefinitionEntity>> apiDefinitionEntityDecoder() {
        return s -> JSON.parseArray(s, ApiDefinitionEntity.class);
    }

    @Bean
    public ConfigService nacosConfigService(NacosPropertiesConfiguration nacosPropertiesConfiguration) throws Exception {
        Properties properties = new Properties();
        properties.put(PropertyKeyConst.SERVER_ADDR, nacosPropertiesConfiguration.getServerAddr());
        properties.put(PropertyKeyConst.NAMESPACE, nacosPropertiesConfiguration.getNamespace());
        properties.put(PropertyKeyConst.USERNAME, nacosPropertiesConfiguration.getUsername());
        properties.put(PropertyKeyConst.PASSWORD, nacosPropertiesConfiguration.getPassword());
        return ConfigFactory.createConfigService(properties);
//        return ConfigFactory.createConfigService("localhost");
    }
}
