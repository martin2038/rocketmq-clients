package org.apache.rocketmq.client.impl.consumer;

import static com.google.common.base.Preconditions.checkNotNull;

import apache.rocketmq.v1.AckMessageRequest;
import apache.rocketmq.v1.AckMessageResponse;
import apache.rocketmq.v1.ClientResourceBundle;
import apache.rocketmq.v1.ConsumeMessageType;
import apache.rocketmq.v1.ConsumeModel;
import apache.rocketmq.v1.ConsumePolicy;
import apache.rocketmq.v1.ConsumerGroup;
import apache.rocketmq.v1.DeadLetterPolicy;
import apache.rocketmq.v1.FilterType;
import apache.rocketmq.v1.HeartbeatEntry;
import apache.rocketmq.v1.Message;
import apache.rocketmq.v1.NackMessageRequest;
import apache.rocketmq.v1.NackMessageResponse;
import apache.rocketmq.v1.QueryAssignmentRequest;
import apache.rocketmq.v1.QueryAssignmentResponse;
import apache.rocketmq.v1.ReceiveMessageResponse;
import apache.rocketmq.v1.Resource;
import apache.rocketmq.v1.ResponseCommon;
import apache.rocketmq.v1.SubscriptionEntry;
import apache.rocketmq.v1.VerifyMessageConsumptionRequest;
import apache.rocketmq.v1.VerifyMessageConsumptionResponse;
import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import com.google.rpc.Code;
import com.google.rpc.Status;
import io.grpc.Metadata;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.constant.ConsumeFromWhere;
import org.apache.rocketmq.client.constant.Permission;
import org.apache.rocketmq.client.constant.ServiceState;
import org.apache.rocketmq.client.consumer.MessageModel;
import org.apache.rocketmq.client.consumer.PopResult;
import org.apache.rocketmq.client.consumer.PopStatus;
import org.apache.rocketmq.client.consumer.filter.FilterExpression;
import org.apache.rocketmq.client.consumer.listener.ConsumeStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.ClientException;
import org.apache.rocketmq.client.exception.ErrorCode;
import org.apache.rocketmq.client.exception.ServerException;
import org.apache.rocketmq.client.impl.ClientBaseImpl;
import org.apache.rocketmq.client.message.MessageExt;
import org.apache.rocketmq.client.message.MessageImpl;
import org.apache.rocketmq.client.message.MessageQueue;
import org.apache.rocketmq.client.misc.MixAll;
import org.apache.rocketmq.client.remoting.Endpoints;
import org.apache.rocketmq.client.route.Broker;
import org.apache.rocketmq.client.route.Partition;
import org.apache.rocketmq.client.route.TopicRouteData;


@Slf4j
public class DefaultMQPushConsumerImpl extends ClientBaseImpl {

    public AtomicLong popTimes;
    public AtomicLong poppedMsgCount;
    public AtomicLong consumeSuccessMsgCount;
    public AtomicLong consumeFailureMsgCount;

    private final ConcurrentMap<String /* topic */, FilterExpression> filterExpressionTable;
    private final ConcurrentMap<String /* topic */, TopicAssignment> cachedTopicAssignmentTable;

    private MessageListenerConcurrently messageListenerConcurrently;
    private MessageListenerOrderly messageListenerOrderly;

    private final ConcurrentMap<MessageQueue, ProcessQueue> processQueueTable;
    private volatile ScheduledFuture<?> scanLoadAssignmentsFuture;

    @Setter
    @Getter
    private int consumeMessageBatchMaxSize = 1;

    @Setter
    @Getter
    private long maxBatchConsumeWaitTimeMillis = 1000;

    @Getter
    @Setter
    private int consumeThreadMin = 20;

    @Getter
    @Setter
    private int consumeThreadMax = 64;

    @Getter
    @Setter
    // Only for order message.
    private long suspendCurrentQueueTimeMillis = 1000;

    @Getter
    private volatile ConsumeService consumeService;

    @Getter
    @Setter
    private int maxReconsumeTimes = 16;

    @Getter
    @Setter
    private MessageModel messageModel = MessageModel.CLUSTERING;

    @Getter
    @Setter
    private ConsumeFromWhere consumeFromWhere = ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET;

    public DefaultMQPushConsumerImpl(String group) {
        super(group);
        this.filterExpressionTable = new ConcurrentHashMap<String, FilterExpression>();
        this.cachedTopicAssignmentTable = new ConcurrentHashMap<String, TopicAssignment>();

        this.messageListenerConcurrently = null;
        this.messageListenerOrderly = null;

        this.processQueueTable = new ConcurrentHashMap<MessageQueue, ProcessQueue>();

        this.consumeService = null;

        this.popTimes = new AtomicLong(0);
        this.poppedMsgCount = new AtomicLong(0);
        this.consumeSuccessMsgCount = new AtomicLong(0);
        this.consumeFailureMsgCount = new AtomicLong(0);
    }

    private ConsumeService generateConsumeService() throws ClientException {
        if (null != messageListenerConcurrently) {
            return new ConsumeConcurrentlyService(this, messageListenerConcurrently);
        }
        if (null != messageListenerOrderly) {
            return new ConsumeOrderlyService(this, messageListenerOrderly);
        }
        throw new ClientException(ErrorCode.NO_LISTENER_REGISTERED);
    }

    @Override
    public void start() throws ClientException {
        synchronized (this) {
            log.info("Begin to start the rocketmq push consumer");
            super.start();

            consumeService = this.generateConsumeService();
            consumeService.start();
            final ScheduledExecutorService scheduler = clientInstance.getScheduler();
            scanLoadAssignmentsFuture = scheduler.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            try {
                                scanAssignments();
                            } catch (Throwable t) {
                                log.error("Exception raised while scanning the load assignments.", t);
                            }
                        }
                    },
                    1,
                    5,
                    TimeUnit.SECONDS);
            if (ServiceState.STARTED == getState()) {
                log.info("The rocketmq push consumer starts successfully.");
            }
        }
    }

    @Override
    public void shutdown() {
        synchronized (this) {
            log.info("Begin to shutdown the rocketmq push consumer.");
            super.shutdown();

            if (ServiceState.STOPPED == getState()) {
                if (null != scanLoadAssignmentsFuture) {
                    scanLoadAssignmentsFuture.cancel(false);
                }
                super.shutdown();
                if (null != consumeService) {
                    consumeService.shutdown();
                }
                log.info("Shutdown the rocketmq push consumer successfully.");
            }
        }
    }

    private QueryAssignmentRequest wrapQueryAssignmentRequest(String topic) {
        Resource topicResource = Resource.newBuilder().setArn(arn).setName(topic).build();
        return QueryAssignmentRequest.newBuilder()
                                     .setTopic(topicResource).setGroup(getGroupResource())
                                     .setClientId(clientId)
                                     .build();
    }

    public void scanAssignments() {
        try {
            log.debug("Start to scan assignments periodically");
            for (Map.Entry<String, FilterExpression> entry : filterExpressionTable.entrySet()) {
                final String topic = entry.getKey();
                final FilterExpression filterExpression = entry.getValue();

                final TopicAssignment topicAssignment = cachedTopicAssignmentTable.get(topic);

                final ListenableFuture<TopicAssignment> future = queryAssignment(topic);
                Futures.addCallback(future, new FutureCallback<TopicAssignment>() {
                    @Override
                    public void onSuccess(TopicAssignment remoteTopicAssignment) {
                        // remoteTopicAssignmentInfo should never be null.
                        if (remoteTopicAssignment.getAssignmentList().isEmpty()) {
                            log.warn("Acquired empty assignment list from remote, topic={}", topic);
                            if (null == topicAssignment || topicAssignment.getAssignmentList().isEmpty()) {
                                log.warn("No available assignments now, would scan later, topic={}", topic);
                                return;
                            }
                            log.warn("Acquired empty assignment list from remote, reuse the existing one, topic={}",
                                     topic);
                            return;
                        }

                        if (!remoteTopicAssignment.equals(topicAssignment)) {
                            log.info("Assignment of topic={} has changed, {} -> {}", topic, topicAssignment,
                                     remoteTopicAssignment);
                            syncProcessQueueByTopic(topic, remoteTopicAssignment, filterExpression);
                            cachedTopicAssignmentTable.put(topic, remoteTopicAssignment);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        log.error("Exception raised while scanning the assignments, topic={}", topic, t);
                    }
                });
            }
        } catch (Throwable t) {
            log.error("Exception raised while scanning the assignments for all topics.", t);
        }
    }

    @Override
    public void logStats() {
        final long popTimes = this.popTimes.getAndSet(0);
        final long poppedMsgCount = this.poppedMsgCount.getAndSet(0);
        final long consumeSuccessMsgCount = this.consumeSuccessMsgCount.getAndSet(0);
        final long consumeFailureMsgCount = this.consumeFailureMsgCount.getAndSet(0);
        log.info(
                "ConsumerGroup={}, PopTimes={}, PoppedMsgCount={}, ConsumeSuccessMsgCount={}, "
                + "ConsumeFailureMsgCount={}", group,
                popTimes,
                poppedMsgCount,
                consumeSuccessMsgCount,
                consumeFailureMsgCount);
    }

    private void syncProcessQueueByTopic(
            String topic, TopicAssignment topicAssignment, FilterExpression filterExpression) {
        Set<MessageQueue> latestMessageQueueSet = new HashSet<MessageQueue>();

        final List<Assignment> assignmentList = topicAssignment.getAssignmentList();
        for (Assignment assignment : assignmentList) {
            latestMessageQueueSet.add(assignment.getMessageQueue());
        }

        Set<MessageQueue> activeMessageQueueSet = new HashSet<MessageQueue>();

        for (Map.Entry<MessageQueue, ProcessQueue> entry : processQueueTable.entrySet()) {
            final MessageQueue messageQueue = entry.getKey();
            final ProcessQueue processQueue = entry.getValue();
            if (!topic.equals(messageQueue.getTopic())) {
                continue;
            }

            if (null == processQueue) {
                log.error("[Bug] Process queue is null, mq={}", messageQueue.simpleName());
                continue;
            }

            if (!latestMessageQueueSet.contains(messageQueue)) {
                log.info(
                        "Stop to pop message queue according to the latest load assignments, mq={}",
                        messageQueue.simpleName());
                processQueueTable.remove(messageQueue);
                processQueue.setDropped(true);
                continue;
            }

            if (processQueue.isPopExpired()) {
                log.warn("ProcessQueue is expired to pop, mq={}", messageQueue.simpleName());
                processQueueTable.remove(messageQueue);
                processQueue.setDropped(true);
                continue;
            }
            activeMessageQueueSet.add(messageQueue);
        }

        for (MessageQueue messageQueue : latestMessageQueueSet) {
            if (!activeMessageQueueSet.contains(messageQueue)) {
                log.info("Start to pop message queue according to the latest assignments, mq={}",
                         messageQueue.simpleName());
                final ProcessQueue processQueue = getProcessQueue(messageQueue, filterExpression);
                processQueue.popMessage();
            }
        }
    }

    private ProcessQueue getProcessQueue(
            MessageQueue messageQueue, final FilterExpression filterExpression) {
        if (null == processQueueTable.get(messageQueue)) {
            processQueueTable.putIfAbsent(
                    messageQueue, new ProcessQueue(this, messageQueue, filterExpression));
        }
        return processQueueTable.get(messageQueue);
    }

    public void subscribe(final String topic, final String subscribeExpression)
            throws ClientException {
        FilterExpression filterExpression = new FilterExpression(subscribeExpression);
        if (!filterExpression.verifyExpression()) {
            throw new ClientException("SubscribeExpression is illegal");
        }
        filterExpressionTable.put(topic, filterExpression);
    }

    public void unsubscribe(final String topic) {
        filterExpressionTable.remove(topic);
    }

    public void registerMessageListener(MessageListenerConcurrently messageListenerConcurrently) {
        checkNotNull(messageListenerConcurrently);
        this.messageListenerConcurrently = messageListenerConcurrently;
    }

    public void registerMessageListener(MessageListenerOrderly messageListenerOrderly) {
        checkNotNull(messageListenerOrderly);
        this.messageListenerOrderly = messageListenerOrderly;
    }

    private ListenableFuture<Endpoints> pickRouteEndpoints(String topic) {
        final ListenableFuture<TopicRouteData> future = getRouteFor(topic);
        return Futures.transformAsync(future, new AsyncFunction<TopicRouteData, Endpoints>() {
            @Override
            public ListenableFuture<Endpoints> apply(TopicRouteData topicRouteData) throws Exception {
                final SettableFuture<Endpoints> future0 = SettableFuture.create();
                final List<Partition> partitions = topicRouteData.getPartitions();
                for (int i = 0; i < partitions.size(); i++) {
                    final Partition partition =
                            partitions.get(TopicAssignment.getNextPartitionIndex() % partitions.size());
                    final Broker broker = partition.getBroker();
                    if (MixAll.MASTER_BROKER_ID != broker.getId()) {
                        continue;
                    }
                    if (Permission.NONE == partition.getPermission()) {
                        continue;
                    }
                    future0.set(broker.getEndpoints());
                    return future0;
                }
                throw new ServerException(ErrorCode.NO_PERMISSION);
            }
        });
    }

    private ListenableFuture<TopicAssignment> queryAssignment(final String topic) {

        final QueryAssignmentRequest request = wrapQueryAssignmentRequest(topic);
        final ListenableFuture<Endpoints> future = pickRouteEndpoints(topic);
        final ListenableFuture<QueryAssignmentResponse> responseFuture =
                Futures.transformAsync(future, new AsyncFunction<Endpoints, QueryAssignmentResponse>() {
                    @Override
                    public ListenableFuture<QueryAssignmentResponse> apply(Endpoints endpoints) throws Exception {
                        final Metadata metadata = sign();
                        return clientInstance.queryAssignment(endpoints, metadata, request, ioTimeoutMillis,
                                                              TimeUnit.MILLISECONDS);
                    }
                });
        return Futures.transformAsync(responseFuture, new AsyncFunction<QueryAssignmentResponse, TopicAssignment>() {
            @Override
            public ListenableFuture<TopicAssignment> apply(QueryAssignmentResponse response) throws Exception {
                SettableFuture<TopicAssignment> future0 = SettableFuture.create();
                final Status status = response.getCommon().getStatus();
                final Code code = Code.forNumber(status.getCode());
                if (Code.OK != code) {
                    log.error("Failed to query assignment, topic={}, code={}, message={}", topic, code,
                              status.getMessage());
                    throw new ClientException(ErrorCode.NO_ASSIGNMENT);
                }
                final TopicAssignment topicAssignment = new TopicAssignment(response.getAssignmentsList());
                future0.set(topicAssignment);
                return future0;
            }
        });
    }

    @Override
    public HeartbeatEntry prepareHeartbeatData() {
        Resource groupResource = Resource.newBuilder().setArn(arn).setName(group).build();

        List<SubscriptionEntry> subscriptionEntries = new ArrayList<SubscriptionEntry>();
        for (Map.Entry<String, FilterExpression> entry : filterExpressionTable.entrySet()) {
            final String topic = entry.getKey();
            final FilterExpression filterExpression = entry.getValue();

            Resource topicResource = Resource.newBuilder().setArn(arn).setName(topic).build();
            final apache.rocketmq.v1.FilterExpression.Builder builder =
                    apache.rocketmq.v1.FilterExpression.newBuilder().setExpression(filterExpression.getExpression());
            switch (filterExpression.getExpressionType()) {
                case TAG:
                    builder.setType(FilterType.TAG);
                    break;
                case SQL92:
                default:
                    builder.setType(FilterType.SQL);
            }
            final apache.rocketmq.v1.FilterExpression expression = builder.build();
            SubscriptionEntry subscriptionEntry =
                    SubscriptionEntry.newBuilder().setTopic(topicResource).setExpression(expression).build();
            subscriptionEntries.add(subscriptionEntry);
        }

        DeadLetterPolicy deadLetterPolicy =
                DeadLetterPolicy.newBuilder()
                                .setMaxDeliveryAttempts(maxReconsumeTimes)
                                .build();

        final ConsumerGroup.Builder builder =
                ConsumerGroup.newBuilder()
                             .setGroup(groupResource)
                             .addAllSubscriptions(subscriptionEntries)
                             .setDeadLetterPolicy(deadLetterPolicy)
                             .setConsumeType(ConsumeMessageType.POP);

        switch (messageModel) {
            case CLUSTERING:
                builder.setConsumeModel(ConsumeModel.CLUSTERING);
                break;
            case BROADCASTING:
                builder.setConsumeModel(ConsumeModel.BROADCASTING);
                break;
            default:
                builder.setConsumeModel(ConsumeModel.UNRECOGNIZED);
        }

        switch (consumeFromWhere) {
            case CONSUME_FROM_FIRST_OFFSET:
                builder.setConsumePolicy(ConsumePolicy.PLAYBACK);
                break;
            case CONSUME_FROM_TIMESTAMP:
                builder.setConsumePolicy(ConsumePolicy.TARGET_TIMESTAMP);
                break;
            case CONSUME_FROM_LAST_OFFSET:
            default:
                builder.setConsumePolicy(ConsumePolicy.RESUME);
        }
        final ConsumerGroup consumerGroup = builder.build();
        return HeartbeatEntry.newBuilder()
                             .setClientId(clientId)
                             .setConsumerGroup(consumerGroup)
                             .build();
    }

    private Resource getGroupResource() {
        return Resource.newBuilder().setArn(arn).setName(group).build();
    }

    public void nackMessage(MessageExt messageExt) throws ClientException {
        final NackMessageRequest request = wrapNackMessageRequest(messageExt);
        final Endpoints endpoints = messageExt.getAckEndpoints();
        final Metadata metadata = sign();
        final ListenableFuture<NackMessageResponse> future =
                clientInstance.nackMessage(endpoints, metadata, request, ioTimeoutMillis, TimeUnit.MILLISECONDS);
        final String messageId = request.getMessageId();
        Futures.addCallback(future, new FutureCallback<NackMessageResponse>() {
            @Override
            public void onSuccess(NackMessageResponse response) {
                final Status status = response.getCommon().getStatus();
                final Code code = Code.forNumber(status.getCode());
                if (Code.OK != code) {
                    log.error("Failed to nack message, messageId={}, endpoints={}, code={}, status message={}",
                              messageId, endpoints, code, status.getMessage());
                    return;
                }
                log.trace("Nack message successfully, messageId={}, endpoints={}, code={}, status message={}",
                          messageId, endpoints, code, status.getMessage());
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Exception raised while nack message, messageId={}, endpoints={}", messageId, endpoints, t);
            }
        });
    }

    private NackMessageRequest wrapNackMessageRequest(MessageExt messageExt) {
        // Group
        final Resource groupResource = Resource.newBuilder()
                                               .setArn(arn)
                                               .setName(group)
                                               .build();
        // Topic
        final Resource topicResource = Resource.newBuilder()
                                               .setArn(arn)
                                               .setName(messageExt.getTopic())
                                               .build();

        final NackMessageRequest.Builder builder =
                NackMessageRequest.newBuilder()
                                  .setGroup(groupResource)
                                  .setTopic(topicResource)
                                  .setClientId(this.getClientId())
                                  .setReceiptHandle(messageExt.getReceiptHandle())
                                  .setMessageId(messageExt.getMsgId())
                                  .setReconsumeTimes(messageExt.getReconsumeTimes() + 1)
                                  .setMaxReconsumeTimes(this.getMaxReconsumeTimes());

        switch (getMessageModel()) {
            case CLUSTERING:
                builder.setConsumeModel(ConsumeModel.CLUSTERING);
                break;
            case BROADCASTING:
                builder.setConsumeModel(ConsumeModel.BROADCASTING);
                break;
            default:
                builder.setConsumeModel(ConsumeModel.UNRECOGNIZED);
        }

        return builder.build();
    }

    private AckMessageRequest wrapAckMessageRequest(MessageExt messageExt) {
        // Group
        final Resource groupResource = Resource.newBuilder()
                                               .setArn(arn)
                                               .setName(this.getGroup())
                                               .build();
        // Topic
        final Resource topicResource = Resource.newBuilder()
                                               .setArn(arn)
                                               .setName(messageExt.getTopic())
                                               .build();

        final AckMessageRequest.Builder builder = AckMessageRequest.newBuilder()
                                                                   .setGroup(groupResource)
                                                                   .setTopic(topicResource)
                                                                   .setMessageId(messageExt.getMsgId())
                                                                   .setClientId(this.getClientId())
                                                                   .setReceiptHandle(messageExt.getReceiptHandle());

        return builder.build();
    }

    public void ackMessage(final MessageExt messageExt) throws ClientException {
        final AckMessageRequest request = wrapAckMessageRequest(messageExt);
        final Endpoints endpoints = messageExt.getAckEndpoints();
        final Metadata metadata = sign();
        final ListenableFuture<AckMessageResponse> future =
                clientInstance.ackMessage(endpoints, metadata, request, ioTimeoutMillis, TimeUnit.MILLISECONDS);
        final String messageId = request.getMessageId();
        Futures.addCallback(future, new FutureCallback<AckMessageResponse>() {
            @Override
            public void onSuccess(AckMessageResponse response) {
                final Status status = response.getCommon().getStatus();
                final Code code = Code.forNumber(status.getCode());
                if (Code.OK != code) {
                    log.error("Failed to ack message, messageId={}, endpoints={}, code={}, message={}", messageId,
                              endpoints, code, status.getMessage());
                    return;
                }
                log.trace("Ack message successfully, messageId={}, endpoints={}, code={}, message={}", messageId,
                          endpoints, code, status.getMessage());
            }

            @Override
            public void onFailure(Throwable t) {
                log.error("Exception raised while ack message, messageId={}, endpoints={}", messageId, endpoints, t);
            }
        });
    }

    @Override
    public ListenableFuture<VerifyMessageConsumptionResponse> verifyConsumption(VerifyMessageConsumptionRequest
                                                                                        request) {
        final ListenableFuture<ConsumeStatus> future = verifyConsumption0(request);
        return Futures.transform(future, new Function<ConsumeStatus, VerifyMessageConsumptionResponse>() {
            @Override
            public VerifyMessageConsumptionResponse apply(ConsumeStatus consumeStatus) {
                final Status.Builder builder = Status.newBuilder();
                Status status;
                switch (consumeStatus) {
                    case OK:
                        status = builder.setCode(Code.OK_VALUE).build();
                        break;
                    case ERROR:
                    default:
                        status = builder.setCode(Code.ABORTED_VALUE).build();
                        break;
                }
                ResponseCommon common = ResponseCommon.newBuilder().setStatus(status).build();
                return VerifyMessageConsumptionResponse.newBuilder().setCommon(common).build();
            }
        });
    }

    public ListenableFuture<ConsumeStatus> verifyConsumption0(VerifyMessageConsumptionRequest request) {
        final Partition partition = new Partition(request.getPartition());
        final Message message = request.getMessage();
        MessageImpl messageImpl;
        try {
            messageImpl = ClientBaseImpl.wrapMessageImpl(message);
        } catch (Throwable t) {
            log.error("Message verify consumption is corrupted, partition={}, messageId={}", partition,
                      message.getSystemAttribute().getMessageId());
            SettableFuture<ConsumeStatus> future0 = SettableFuture.create();
            future0.setException(t);
            return future0;
        }
        final MessageExt messageExt = new MessageExt(messageImpl);
        return consumeService.verifyConsumption(messageExt, partition);
    }

    @Override
    public ClientResourceBundle wrapClientResourceBundle() {
        Resource groupResource = Resource.newBuilder().setArn(arn).setName(group).build();
        final ClientResourceBundle.Builder builder =
                ClientResourceBundle.newBuilder().setClientId(clientId).setProducerGroup(groupResource);
        for (String topic : filterExpressionTable.keySet()) {
            Resource topicResource = Resource.newBuilder().setArn(arn).setName(topic).build();
            builder.addTopics(topicResource);
        }
        return builder.build();
    }

    // TODO: handle the case that the topic does not exist.
    public static PopResult processReceiveMessageResponse(Endpoints endpoints, ReceiveMessageResponse response) {
        PopStatus popStatus;

        final Status status = response.getCommon().getStatus();
        final Code code = Code.forNumber(status.getCode());
        switch (code != null ? code : Code.UNKNOWN) {
            case OK:
                popStatus = PopStatus.OK;
                break;
            case RESOURCE_EXHAUSTED:
                popStatus = PopStatus.RESOURCE_EXHAUSTED;
                log.warn("Too many request in server, server endpoints={}, status message={}", endpoints,
                         status.getMessage());
                break;
            case DEADLINE_EXCEEDED:
                popStatus = PopStatus.DEADLINE_EXCEEDED;
                log.warn("Gateway timeout, server endpoints={}, status message={}", endpoints, status.getMessage());
                break;
            default:
                popStatus = PopStatus.INTERNAL;
                log.warn("Pop response indicated server-side error, server endpoints={}, code={}, status message={}",
                         endpoints, code, status.getMessage());
        }

        List<MessageExt> msgFoundList = new ArrayList<MessageExt>();
        if (PopStatus.OK == popStatus) {
            final List<Message> messageList = response.getMessagesList();
            for (Message message : messageList) {
                try {
                    MessageImpl messageImpl = ClientBaseImpl.wrapMessageImpl(message);
                    messageImpl.getSystemAttribute().setAckEndpoints(endpoints);
                    msgFoundList.add(new MessageExt(messageImpl));
                } catch (ClientException e) {
                    // TODO: need nack immediately.
                    log.error("Failed to wrap messageImpl, topic={}, messageId={}", message.getTopic(),
                              message.getSystemAttribute().getMessageId(), e);
                } catch (IOException e) {
                    log.error("Failed to wrap messageImpl, topic={}, messageId={}", message.getTopic(),
                              message.getSystemAttribute().getMessageId(), e);
                } catch (Throwable t) {
                    log.error("Exception raised while wrapping messageImpl, topic={}, messageId={}",
                              message.getTopic(), message.getSystemAttribute().getMessageId(), t);
                }
            }
        }

        return new PopResult(endpoints, popStatus, Timestamps.toMillis(response.getDeliveryTimestamp()),
                             Durations.toMillis(response.getInvisibleDuration()), msgFoundList);
    }
}
