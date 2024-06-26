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

package org.apache.rocketmq.client.java.impl;

import io.grpc.LoadBalancerRegistry;
import io.grpc.ManagedChannelProvider;
import io.grpc.NameResolverRegistry;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.PushConsumerBuilder;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumerBuilder;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.java.impl.consumer.PushConsumerBuilderImpl;
import org.apache.rocketmq.client.java.impl.consumer.SimpleConsumerBuilderImpl;
import org.apache.rocketmq.client.java.impl.producer.ProducerBuilderImpl;
import org.apache.rocketmq.client.java.message.MessageBuilderImpl;

public class ClientServiceProviderImpl implements ClientServiceProvider {



    /**
     * @see ClientServiceProvider#newProducerBuilder()
     */
    @Override
    public ProducerBuilder newProducerBuilder() {
        return new ProducerBuilderImpl();
    }

    /**
     * @see ClientServiceProvider#newMessageBuilder()
     */
    @Override
    public PushConsumerBuilder newPushConsumerBuilder() {
        return new PushConsumerBuilderImpl();
    }

    /**
     * @see ClientServiceProvider#newMessageBuilder()
     */
    @Override
    public SimpleConsumerBuilder newSimpleConsumerBuilder() {
        return new SimpleConsumerBuilderImpl();
    }

    /**
     * @see ClientServiceProvider#newMessageBuilder()
     */
    @Override
    public MessageBuilder newMessageBuilder() {
        return new MessageBuilderImpl();
    }
}
