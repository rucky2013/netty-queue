package org.mitallast.queue.blob;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.typesafe.config.Config;
import org.mitallast.queue.Version;
import org.mitallast.queue.blob.protocol.*;
import org.mitallast.queue.common.component.AbstractComponent;
import org.mitallast.queue.raft.Raft;
import org.mitallast.queue.raft.protocol.ClientMessage;
import org.mitallast.queue.transport.*;
import org.mitallast.queue.transport.netty.codec.MessageTransportFrame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public class DistributedStorageService extends AbstractComponent {

    private final TransportServer transportServer;
    private final TransportService transportService;
    private final BlobStorageService blobStorageService;
    private final Raft raft;
    private final DistributedStorageFSM fsm;
    private final int QoS;
    private final ConcurrentMap<Long, CompletableFuture> requests = new ConcurrentHashMap<>();
    private final AtomicLong requestId = new AtomicLong();

    @Inject
    public DistributedStorageService(
            Config config,
            TransportController transportController,
            TransportServer transportServer,
            TransportService transportService,
            BlobStorageService blobStorageService,
            Raft raft,
            DistributedStorageFSM fsm
    ) {
        super(config.getConfig("blob"), DistributedStorageService.class);
        this.transportServer = transportServer;
        this.transportService = transportService;
        this.blobStorageService = blobStorageService;
        this.raft = raft;
        this.fsm = fsm;
        this.QoS = this.config.getInt("QoS");

        transportController.<PutBlobResourceRequest>registerMessageHandler(PutBlobResourceRequest.class, this::handle);
        transportController.<PutBlobResourceResponse>registerMessageHandler(PutBlobResourceResponse.class, this::handle);
        transportController.<GetBlobResourceRequest>registerMessageHandler(GetBlobResourceRequest.class, this::handle);
        transportController.<GetBlobResourceResponse>registerMessageHandler(GetBlobResourceResponse.class, this::handle);
    }

    public CompletableFuture<Boolean> putResource(String key, byte[] data) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        List<DiscoveryNode> replicas = new ArrayList<>(raft.currentMeta().getConfig().members());
        for (int i = 0, max = replicas.size() - 1; i < max; i++) {
            int swap = new Random().nextInt(replicas.size() - max) + 1;
            DiscoveryNode a = replicas.get(i);
            DiscoveryNode b = replicas.get(swap);
            replicas.set(i, b);
            replicas.set(swap, a);
        }
        for (int i = 0; i < QoS; i++) {
            CompletableFuture<PutBlobResourceResponse> nodeFuture = new CompletableFuture<>();
            long id = requestId.incrementAndGet();
            requests.put(id, nodeFuture);
            nodeFuture.whenComplete(new BiConsumer<PutBlobResourceResponse, Throwable>() {

                private AtomicLong countDown = new AtomicLong();
                private boolean stored = false;

                @Override
                public void accept(PutBlobResourceResponse response, Throwable throwable) {
                    if (countDown.decrementAndGet() == 0) {
                        stored = stored || response.isStored();
                        if (stored) {
                            future.complete(null);
                        }
                    }
                }
            });

            PutBlobResourceRequest message = new PutBlobResourceRequest(id, key, data);
            MessageTransportFrame frame = new MessageTransportFrame(Version.CURRENT, message);
            transportService.connectToNode(replicas.get(i));
            transportService.channel(replicas.get(i)).send(frame);
        }
        return future;
    }

    public CompletableFuture<GetBlobResourceResponse> getResource(String key) {
        logger.info("get resource {}", key);
        CompletableFuture<GetBlobResourceResponse> future = new CompletableFuture<>();
        ImmutableMap<String, ImmutableList<DiscoveryNode>> routingMap = fsm.getRoutingMap().getRoutingMap();
        if (routingMap.containsKey(key)) {
            ImmutableList<DiscoveryNode> nodes = routingMap.get(key);

            long id = requestId.incrementAndGet();
            requests.put(id, future);

            DiscoveryNode node = nodes.get((int) (id % nodes.size()));

            logger.info("get resource {} id {} node {}", key, id, node);
            MessageTransportFrame frame = new MessageTransportFrame(Version.CURRENT, new GetBlobResourceRequest(id, key));
            transportService.channel(node).send(frame);
        } else {
            future.completeExceptionally(new RuntimeException("resource not found"));
        }
        return future;
    }

    private void handle(TransportChannel channel, GetBlobResourceRequest message) {
        try {
            logger.info("handle get resource request {} id {}", message.getKey(), message.getId());
            InputStream stream = blobStorageService.getObject(message.getKey());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[4096];
            int read;
            while ((read = stream.read(bytes)) > 0) {
                out.write(bytes, 0, read);
            }
            GetBlobResourceResponse response = new GetBlobResourceResponse(message.getId(), message.getKey(), out.toByteArray());
            channel.send(new MessageTransportFrame(Version.CURRENT, response));
        } catch (IOException e) {
            logger.warn("error get resource {}: {}", message.getKey(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(TransportChannel channel, GetBlobResourceResponse message) {
        logger.info("handle get resource response {} id {}", message.getKey(), message.getId());
        CompletableFuture completableFuture = requests.get(message.getId());
        if (completableFuture != null) {
            completableFuture.complete(message);
        }
    }

    private void handle(TransportChannel channel, PutBlobResourceRequest message) {
        boolean stored = false;
        try {
            blobStorageService.putObject(message.getKey(), new ByteArrayInputStream(message.getData()));
            stored = true;
        } catch (IOException e) {
            logger.error("error store: {}", e);
        }

        PutBlobResource cmd = new PutBlobResource(message.getKey(), transportServer.localNode());
        raft.receive(new ClientMessage(transportServer.localNode(), cmd));

        PutBlobResourceResponse response = new PutBlobResourceResponse(
                message.getId(),
                transportServer.localNode(),
                message.getKey(),
                stored
        );
        channel.send(new MessageTransportFrame(Version.CURRENT, response));
    }

    @SuppressWarnings("unchecked")
    private void handle(TransportChannel channel, PutBlobResourceResponse message) {
        CompletableFuture completableFuture = requests.get(message.getId());
        if (completableFuture != null) {
            completableFuture.complete(message);
        }
    }
}