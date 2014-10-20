package org.onlab.onos.store.link.impl;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;

import org.apache.commons.lang3.RandomUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.onos.cluster.ClusterService;
import org.onlab.onos.cluster.ControllerNode;
import org.onlab.onos.cluster.NodeId;
import org.onlab.onos.net.AnnotationsUtil;
import org.onlab.onos.net.ConnectPoint;
import org.onlab.onos.net.DefaultAnnotations;
import org.onlab.onos.net.DefaultLink;
import org.onlab.onos.net.DeviceId;
import org.onlab.onos.net.Link;
import org.onlab.onos.net.SparseAnnotations;
import org.onlab.onos.net.Link.Type;
import org.onlab.onos.net.LinkKey;
import org.onlab.onos.net.device.DeviceClockService;
import org.onlab.onos.net.link.DefaultLinkDescription;
import org.onlab.onos.net.link.LinkDescription;
import org.onlab.onos.net.link.LinkEvent;
import org.onlab.onos.net.link.LinkStore;
import org.onlab.onos.net.link.LinkStoreDelegate;
import org.onlab.onos.net.provider.ProviderId;
import org.onlab.onos.store.AbstractStore;
import org.onlab.onos.store.Timestamp;
import org.onlab.onos.store.cluster.messaging.ClusterCommunicationService;
import org.onlab.onos.store.cluster.messaging.ClusterMessage;
import org.onlab.onos.store.cluster.messaging.ClusterMessageHandler;
import org.onlab.onos.store.cluster.messaging.MessageSubject;
import org.onlab.onos.store.impl.Timestamped;
import org.onlab.onos.store.serializers.DistributedStoreSerializers;
import org.onlab.onos.store.serializers.KryoSerializer;
import org.onlab.util.KryoNamespace;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.onlab.onos.cluster.ControllerNodeToNodeId.toNodeId;
import static org.onlab.onos.net.DefaultAnnotations.union;
import static org.onlab.onos.net.DefaultAnnotations.merge;
import static org.onlab.onos.net.Link.Type.DIRECT;
import static org.onlab.onos.net.Link.Type.INDIRECT;
import static org.onlab.onos.net.LinkKey.linkKey;
import static org.onlab.onos.net.link.LinkEvent.Type.*;
import static org.onlab.util.Tools.namedThreads;
import static org.slf4j.LoggerFactory.getLogger;
import static com.google.common.collect.Multimaps.synchronizedSetMultimap;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Predicates.notNull;
import static org.onlab.onos.store.link.impl.GossipLinkStoreMessageSubjects.LINK_ANTI_ENTROPY_ADVERTISEMENT;

/**
 * Manages inventory of infrastructure links in distributed data store
 * that uses optimistic replication and gossip based techniques.
 */
@Component(immediate = true)
@Service
public class GossipLinkStore
        extends AbstractStore<LinkEvent, LinkStoreDelegate>
        implements LinkStore {

    private final Logger log = getLogger(getClass());

    // Link inventory
    private final ConcurrentMap<LinkKey, Map<ProviderId, Timestamped<LinkDescription>>> linkDescs =
        new ConcurrentHashMap<>();

    // Link instance cache
    private final ConcurrentMap<LinkKey, Link> links = new ConcurrentHashMap<>();

    // Egress and ingress link sets
    private final SetMultimap<DeviceId, LinkKey> srcLinks = createSynchronizedHashMultiMap();
    private final SetMultimap<DeviceId, LinkKey> dstLinks = createSynchronizedHashMultiMap();

    // Remove links
    private final Map<LinkKey, Timestamp> removedLinks = Maps.newHashMap();

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceClockService deviceClockService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    private static final KryoSerializer SERIALIZER = new KryoSerializer() {
        @Override
        protected void setupKryoPool() {
            serializerPool = KryoNamespace.newBuilder()
                    .register(DistributedStoreSerializers.COMMON)
                    .register(InternalLinkEvent.class)
                    .register(InternalLinkRemovedEvent.class)
                    .register(LinkAntiEntropyAdvertisement.class)
                    .register(LinkFragmentId.class)
                    .build()
                    .populate(1);
        }
    };

    private ScheduledExecutorService executor;

    @Activate
    public void activate() {

        clusterCommunicator.addSubscriber(
                GossipLinkStoreMessageSubjects.LINK_UPDATE,
                new InternalLinkEventListener());
        clusterCommunicator.addSubscriber(
                GossipLinkStoreMessageSubjects.LINK_REMOVED,
                new InternalLinkRemovedEventListener());
        clusterCommunicator.addSubscriber(
                GossipLinkStoreMessageSubjects.LINK_ANTI_ENTROPY_ADVERTISEMENT,
                new InternalLinkAntiEntropyAdvertisementListener());

        executor =
                newSingleThreadScheduledExecutor(namedThreads("link-anti-entropy-%d"));

        // TODO: Make these configurable
        long initialDelaySec = 5;
        long periodSec = 5;
        // start anti-entropy thread
        executor.scheduleAtFixedRate(new SendAdvertisementTask(),
                    initialDelaySec, periodSec, TimeUnit.SECONDS);

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.error("Timeout during executor shutdown");
            }
        } catch (InterruptedException e) {
            log.error("Error during executor shutdown", e);
        }

        linkDescs.clear();
        links.clear();
        srcLinks.clear();
        dstLinks.clear();
        log.info("Stopped");
    }

    @Override
    public int getLinkCount() {
        return links.size();
    }

    @Override
    public Iterable<Link> getLinks() {
        return Collections.unmodifiableCollection(links.values());
    }

    @Override
    public Set<Link> getDeviceEgressLinks(DeviceId deviceId) {
        // lock for iteration
        synchronized (srcLinks) {
            return FluentIterable.from(srcLinks.get(deviceId))
            .transform(lookupLink())
            .filter(notNull())
            .toSet();
        }
    }

    @Override
    public Set<Link> getDeviceIngressLinks(DeviceId deviceId) {
        // lock for iteration
        synchronized (dstLinks) {
            return FluentIterable.from(dstLinks.get(deviceId))
            .transform(lookupLink())
            .filter(notNull())
            .toSet();
        }
    }

    @Override
    public Link getLink(ConnectPoint src, ConnectPoint dst) {
        return links.get(linkKey(src, dst));
    }

    @Override
    public Set<Link> getEgressLinks(ConnectPoint src) {
        Set<Link> egress = new HashSet<>();
        for (LinkKey linkKey : srcLinks.get(src.deviceId())) {
            if (linkKey.src().equals(src)) {
                egress.add(links.get(linkKey));
            }
        }
        return egress;
    }

    @Override
    public Set<Link> getIngressLinks(ConnectPoint dst) {
        Set<Link> ingress = new HashSet<>();
        for (LinkKey linkKey : dstLinks.get(dst.deviceId())) {
            if (linkKey.dst().equals(dst)) {
                ingress.add(links.get(linkKey));
            }
        }
        return ingress;
    }

    @Override
    public LinkEvent createOrUpdateLink(ProviderId providerId,
                                        LinkDescription linkDescription) {

        DeviceId dstDeviceId = linkDescription.dst().deviceId();
        Timestamp newTimestamp = deviceClockService.getTimestamp(dstDeviceId);

        final Timestamped<LinkDescription> deltaDesc = new Timestamped<>(linkDescription, newTimestamp);

        LinkKey key = linkKey(linkDescription.src(), linkDescription.dst());
        final LinkEvent event;
        final Timestamped<LinkDescription> mergedDesc;
        synchronized (getOrCreateLinkDescriptions(key)) {
            event = createOrUpdateLinkInternal(providerId, deltaDesc);
            mergedDesc = getOrCreateLinkDescriptions(key).get(providerId);
        }

        if (event != null) {
            log.info("Notifying peers of a link update topology event from providerId: "
                    + "{}  between src: {} and dst: {}",
                    providerId, linkDescription.src(), linkDescription.dst());
            try {
                notifyPeers(new InternalLinkEvent(providerId, mergedDesc));
            } catch (IOException e) {
                log.info("Failed to notify peers of a link update topology event from providerId: "
                        + "{}  between src: {} and dst: {}",
                        providerId, linkDescription.src(), linkDescription.dst());
            }
        }
        return event;
    }

    private LinkEvent createOrUpdateLinkInternal(
            ProviderId providerId,
            Timestamped<LinkDescription> linkDescription) {

        LinkKey key = linkKey(linkDescription.value().src(),
                              linkDescription.value().dst());
        Map<ProviderId, Timestamped<LinkDescription>> descs = getOrCreateLinkDescriptions(key);

        synchronized (descs) {
            // if the link was previously removed, we should proceed if and
            // only if this request is more recent.
            Timestamp linkRemovedTimestamp = removedLinks.get(key);
            if (linkRemovedTimestamp != null) {
                if (linkDescription.isNewer(linkRemovedTimestamp)) {
                    removedLinks.remove(key);
                } else {
                    return null;
                }
            }

            final Link oldLink = links.get(key);
            // update description
            createOrUpdateLinkDescription(descs, providerId, linkDescription);
            final Link newLink = composeLink(descs);
            if (oldLink == null) {
                return createLink(key, newLink);
            }
            return updateLink(key, oldLink, newLink);
        }
    }

    // Guarded by linkDescs value (=locking each Link)
    private Timestamped<LinkDescription> createOrUpdateLinkDescription(
            Map<ProviderId, Timestamped<LinkDescription>> descs,
            ProviderId providerId,
            Timestamped<LinkDescription> linkDescription) {

        // merge existing annotations
        Timestamped<LinkDescription> existingLinkDescription = descs.get(providerId);
        if (existingLinkDescription != null && existingLinkDescription.isNewer(linkDescription)) {
            return null;
        }
        Timestamped<LinkDescription> newLinkDescription = linkDescription;
        if (existingLinkDescription != null) {
            SparseAnnotations merged = union(existingLinkDescription.value().annotations(),
                    linkDescription.value().annotations());
            newLinkDescription = new Timestamped<LinkDescription>(
                    new DefaultLinkDescription(
                        linkDescription.value().src(),
                        linkDescription.value().dst(),
                        linkDescription.value().type(), merged),
                    linkDescription.timestamp());
        }
        return descs.put(providerId, newLinkDescription);
    }

    // Creates and stores the link and returns the appropriate event.
    // Guarded by linkDescs value (=locking each Link)
    private LinkEvent createLink(LinkKey key, Link newLink) {

        if (newLink.providerId().isAncillary()) {
            // TODO: revisit ancillary only Link handling

            // currently treating ancillary only as down (not visible outside)
            return null;
        }

        links.put(key, newLink);
        srcLinks.put(newLink.src().deviceId(), key);
        dstLinks.put(newLink.dst().deviceId(), key);
        return new LinkEvent(LINK_ADDED, newLink);
    }

    // Updates, if necessary the specified link and returns the appropriate event.
    // Guarded by linkDescs value (=locking each Link)
    private LinkEvent updateLink(LinkKey key, Link oldLink, Link newLink) {

        if (newLink.providerId().isAncillary()) {
            // TODO: revisit ancillary only Link handling

            // currently treating ancillary only as down (not visible outside)
            return null;
        }

        if ((oldLink.type() == INDIRECT && newLink.type() == DIRECT) ||
            !AnnotationsUtil.isEqual(oldLink.annotations(), newLink.annotations())) {

            links.put(key, newLink);
            // strictly speaking following can be ommitted
            srcLinks.put(oldLink.src().deviceId(), key);
            dstLinks.put(oldLink.dst().deviceId(), key);
            return new LinkEvent(LINK_UPDATED, newLink);
        }
        return null;
    }

    @Override
    public LinkEvent removeLink(ConnectPoint src, ConnectPoint dst) {
        final LinkKey key = linkKey(src, dst);

        DeviceId dstDeviceId = dst.deviceId();
        Timestamp timestamp = null;
        try {
            timestamp = deviceClockService.getTimestamp(dstDeviceId);
        } catch (IllegalStateException e) {
            //there are times when this is called before mastership
            // handoff correctly completes.
            return null;
        }

        LinkEvent event = removeLinkInternal(key, timestamp);

        if (event != null) {
            log.info("Notifying peers of a link removed topology event for a link "
                    + "between src: {} and dst: {}", src, dst);
            try {
                notifyPeers(new InternalLinkRemovedEvent(key, timestamp));
            } catch (IOException e) {
                log.error("Failed to notify peers of a link removed topology event for a link "
                        + "between src: {} and dst: {}", src, dst);
            }
        }
        return event;
    }

    private static Timestamped<LinkDescription> getPrimaryDescription(
                Map<ProviderId, Timestamped<LinkDescription>> linkDescriptions) {

        synchronized (linkDescriptions) {
            for (Entry<ProviderId, Timestamped<LinkDescription>>
                    e : linkDescriptions.entrySet()) {

                if (!e.getKey().isAncillary()) {
                    return e.getValue();
                }
            }
        }
        return null;
    }


    // TODO: consider slicing out as Timestamp utils
    /**
     * Checks is timestamp is more recent than timestamped object.
     *
     * @param timestamp to check if this is more recent then other
     * @param timestamped object to be tested against
     * @return true if {@code timestamp} is more recent than {@code timestamped}
     *         or {@code timestamped is null}
     */
    private static boolean isMoreRecent(Timestamp timestamp, Timestamped<?> timestamped) {
        checkNotNull(timestamp);
        if (timestamped == null) {
            return true;
        }
        return timestamp.compareTo(timestamped.timestamp()) > 0;
    }

    private LinkEvent removeLinkInternal(LinkKey key, Timestamp timestamp) {
        Map<ProviderId, Timestamped<LinkDescription>> linkDescriptions
            = getOrCreateLinkDescriptions(key);

        synchronized (linkDescriptions) {
            if (linkDescriptions.isEmpty()) {
                // never seen such link before. keeping timestamp for record
                removedLinks.put(key, timestamp);
                return null;
            }
            // accept removal request if given timestamp is newer than
            // the latest Timestamp from Primary provider
            Timestamped<LinkDescription> prim = getPrimaryDescription(linkDescriptions);
            if (!isMoreRecent(timestamp, prim)) {
                // outdated remove request, ignore
                return null;
            }
            removedLinks.put(key, timestamp);
            Link link = links.remove(key);
            linkDescriptions.clear();
            if (link != null) {
                srcLinks.remove(link.src().deviceId(), key);
                dstLinks.remove(link.dst().deviceId(), key);
                return new LinkEvent(LINK_REMOVED, link);
            }
            return null;
        }
    }

    private static <K, V> SetMultimap<K, V> createSynchronizedHashMultiMap() {
        return synchronizedSetMultimap(HashMultimap.<K, V>create());
    }

    /**
     * @return primary ProviderID, or randomly chosen one if none exists
     */
    private static ProviderId pickBaseProviderId(
            Map<ProviderId, Timestamped<LinkDescription>> linkDescriptions) {

        ProviderId fallBackPrimary = null;
        for (Entry<ProviderId, Timestamped<LinkDescription>> e : linkDescriptions.entrySet()) {
            if (!e.getKey().isAncillary()) {
                // found primary
                return e.getKey();
            } else if (fallBackPrimary == null) {
                // pick randomly as a fallback in case there is no primary
                fallBackPrimary = e.getKey();
            }
        }
        return fallBackPrimary;
    }

    // Guarded by linkDescs value (=locking each Link)
    private Link composeLink(Map<ProviderId, Timestamped<LinkDescription>> descs) {
        ProviderId baseProviderId = pickBaseProviderId(descs);
        Timestamped<LinkDescription> base = descs.get(baseProviderId);

        ConnectPoint src = base.value().src();
        ConnectPoint dst = base.value().dst();
        Type type = base.value().type();
        DefaultAnnotations annotations = DefaultAnnotations.builder().build();
        annotations = merge(annotations, base.value().annotations());

        for (Entry<ProviderId, Timestamped<LinkDescription>> e : descs.entrySet()) {
            if (baseProviderId.equals(e.getKey())) {
                continue;
            }

            // TODO: should keep track of Description timestamp
            // and only merge conflicting keys when timestamp is newer
            // Currently assuming there will never be a key conflict between
            // providers

            // annotation merging. not so efficient, should revisit later
            annotations = merge(annotations, e.getValue().value().annotations());
        }

        return new DefaultLink(baseProviderId, src, dst, type, annotations);
    }

    private Map<ProviderId, Timestamped<LinkDescription>> getOrCreateLinkDescriptions(LinkKey key) {
        Map<ProviderId, Timestamped<LinkDescription>> r;
        r = linkDescs.get(key);
        if (r != null) {
            return r;
        }
        r = new HashMap<>();
        final Map<ProviderId, Timestamped<LinkDescription>> concurrentlyAdded;
        concurrentlyAdded = linkDescs.putIfAbsent(key, r);
        if (concurrentlyAdded != null) {
            return concurrentlyAdded;
        } else {
            return r;
        }
    }

    private final Function<LinkKey, Link> lookupLink = new LookupLink();
    /**
     * Returns a Function to lookup Link instance using LinkKey from cache.
     * @return
     */
    private Function<LinkKey, Link> lookupLink() {
        return lookupLink;
    }

    private final class LookupLink implements Function<LinkKey, Link> {
        @Override
        public Link apply(LinkKey input) {
            if (input == null) {
                return null;
            } else {
                return links.get(input);
            }
        }
    }

    private void notifyDelegateIfNotNull(LinkEvent event) {
        if (event != null) {
            notifyDelegate(event);
        }
    }

    private void broadcastMessage(MessageSubject subject, Object event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                subject,
                SERIALIZER.encode(event));
        clusterCommunicator.broadcast(message);
    }

    private void unicastMessage(NodeId recipient, MessageSubject subject, Object event) throws IOException {
        ClusterMessage message = new ClusterMessage(
                clusterService.getLocalNode().id(),
                subject,
                SERIALIZER.encode(event));
        clusterCommunicator.unicast(message, recipient);
    }

    private void notifyPeers(InternalLinkEvent event) throws IOException {
        broadcastMessage(GossipLinkStoreMessageSubjects.LINK_UPDATE, event);
    }

    private void notifyPeers(InternalLinkRemovedEvent event) throws IOException {
        broadcastMessage(GossipLinkStoreMessageSubjects.LINK_REMOVED, event);
    }

    // notify peer, silently ignoring error
    private void notifyPeer(NodeId peer, InternalLinkEvent event) {
        try {
            unicastMessage(peer, GossipLinkStoreMessageSubjects.LINK_UPDATE, event);
        } catch (IOException e) {
            log.debug("Failed to notify peer {} with message {}", peer, event);
        }
    }

    // notify peer, silently ignoring error
    private void notifyPeer(NodeId peer, InternalLinkRemovedEvent event) {
        try {
            unicastMessage(peer, GossipLinkStoreMessageSubjects.LINK_REMOVED, event);
        } catch (IOException e) {
            log.debug("Failed to notify peer {} with message {}", peer, event);
        }
    }

    private final class SendAdvertisementTask implements Runnable {

        @Override
        public void run() {
            if (Thread.currentThread().isInterrupted()) {
                log.info("Interrupted, quitting");
                return;
            }

            try {
                final NodeId self = clusterService.getLocalNode().id();
                Set<ControllerNode> nodes = clusterService.getNodes();

                ImmutableList<NodeId> nodeIds = FluentIterable.from(nodes)
                        .transform(toNodeId())
                        .toList();

                if (nodeIds.size() == 1 && nodeIds.get(0).equals(self)) {
                    log.debug("No other peers in the cluster.");
                    return;
                }

                NodeId peer;
                do {
                    int idx = RandomUtils.nextInt(0, nodeIds.size());
                    peer = nodeIds.get(idx);
                } while (peer.equals(self));

                LinkAntiEntropyAdvertisement ad = createAdvertisement();

                if (Thread.currentThread().isInterrupted()) {
                    log.info("Interrupted, quitting");
                    return;
                }

                try {
                    unicastMessage(peer, LINK_ANTI_ENTROPY_ADVERTISEMENT, ad);
                } catch (IOException e) {
                    log.debug("Failed to send anti-entropy advertisement to {}", peer);
                    return;
                }
            } catch (Exception e) {
                // catch all Exception to avoid Scheduled task being suppressed.
                log.error("Exception thrown while sending advertisement", e);
            }
        }
    }

    private LinkAntiEntropyAdvertisement createAdvertisement() {
        final NodeId self = clusterService.getLocalNode().id();

        Map<LinkFragmentId, Timestamp> linkTimestamps = new HashMap<>(linkDescs.size());
        Map<LinkKey, Timestamp> linkTombstones = new HashMap<>(removedLinks.size());

        for (Entry<LinkKey, Map<ProviderId, Timestamped<LinkDescription>>>
            provs : linkDescs.entrySet()) {

            final LinkKey linkKey = provs.getKey();
            final Map<ProviderId, Timestamped<LinkDescription>> linkDesc = provs.getValue();
            synchronized (linkDesc) {
                for (Map.Entry<ProviderId, Timestamped<LinkDescription>> e : linkDesc.entrySet()) {
                    linkTimestamps.put(new LinkFragmentId(linkKey, e.getKey()), e.getValue().timestamp());
                }
            }
        }

        linkTombstones.putAll(removedLinks);

        return new LinkAntiEntropyAdvertisement(self, linkTimestamps, linkTombstones);
    }

    private void handleAntiEntropyAdvertisement(LinkAntiEntropyAdvertisement ad) {

        final NodeId sender = ad.sender();
        boolean localOutdated = false;

        for (Entry<LinkKey, Map<ProviderId, Timestamped<LinkDescription>>>
                l : linkDescs.entrySet()) {

            final LinkKey key = l.getKey();
            final Map<ProviderId, Timestamped<LinkDescription>> link = l.getValue();
            synchronized (link) {
                Timestamp localLatest = removedLinks.get(key);

                for (Entry<ProviderId, Timestamped<LinkDescription>> p : link.entrySet()) {
                    final ProviderId providerId = p.getKey();
                    final Timestamped<LinkDescription> pDesc = p.getValue();

                    final LinkFragmentId fragId = new LinkFragmentId(key, providerId);
                    // remote
                    Timestamp remoteTimestamp = ad.linkTimestamps().get(fragId);
                    if (remoteTimestamp == null) {
                        remoteTimestamp = ad.linkTombstones().get(key);
                    }
                    if (remoteTimestamp == null ||
                        pDesc.isNewer(remoteTimestamp)) {
                        // I have more recent link description. update peer.
                        notifyPeer(sender, new InternalLinkEvent(providerId, pDesc));
                    } else {
                        final Timestamp remoteLive = ad.linkTimestamps().get(fragId);
                        if (remoteLive != null &&
                            remoteLive.compareTo(pDesc.timestamp()) > 0) {
                            // I have something outdated
                            localOutdated = true;
                        }
                    }

                    // search local latest along the way
                    if (localLatest == null ||
                        pDesc.isNewer(localLatest)) {
                        localLatest = pDesc.timestamp();
                    }
                }
                // Tests if remote remove is more recent then local latest.
                final Timestamp remoteRemove = ad.linkTombstones().get(key);
                if (remoteRemove != null) {
                    if (localLatest != null &&
                        localLatest.compareTo(remoteRemove) < 0) {
                        // remote remove is more recent
                        notifyDelegateIfNotNull(removeLinkInternal(key, remoteRemove));
                    }
                }
            }
        }

        // populate remove info if not known locally
        for (Entry<LinkKey, Timestamp> remoteRm : ad.linkTombstones().entrySet()) {
            final LinkKey key = remoteRm.getKey();
            final Timestamp remoteRemove = remoteRm.getValue();
            // relying on removeLinkInternal to ignore stale info
            notifyDelegateIfNotNull(removeLinkInternal(key, remoteRemove));
        }

        if (localOutdated) {
            // send back advertisement to speed up convergence
            try {
                unicastMessage(sender, LINK_ANTI_ENTROPY_ADVERTISEMENT,
                                createAdvertisement());
            } catch (IOException e) {
                log.debug("Failed to send back active advertisement");
            }
        }
    }

    private class InternalLinkEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.trace("Received link event from peer: {}", message.sender());
            InternalLinkEvent event = (InternalLinkEvent) SERIALIZER.decode(message.payload());

            ProviderId providerId = event.providerId();
            Timestamped<LinkDescription> linkDescription = event.linkDescription();

            notifyDelegateIfNotNull(createOrUpdateLinkInternal(providerId, linkDescription));
        }
    }

    private class InternalLinkRemovedEventListener implements ClusterMessageHandler {
        @Override
        public void handle(ClusterMessage message) {

            log.trace("Received link removed event from peer: {}", message.sender());
            InternalLinkRemovedEvent event = (InternalLinkRemovedEvent) SERIALIZER.decode(message.payload());

            LinkKey linkKey = event.linkKey();
            Timestamp timestamp = event.timestamp();

            notifyDelegateIfNotNull(removeLinkInternal(linkKey, timestamp));
        }
    }

    private final class InternalLinkAntiEntropyAdvertisementListener implements ClusterMessageHandler {

        @Override
        public void handle(ClusterMessage message) {
            log.debug("Received Link Anti-Entropy advertisement from peer: {}", message.sender());
            LinkAntiEntropyAdvertisement advertisement = SERIALIZER.decode(message.payload());
            handleAntiEntropyAdvertisement(advertisement);
        }
    }
}
