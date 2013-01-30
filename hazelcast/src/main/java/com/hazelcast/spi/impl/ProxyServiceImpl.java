/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl;

import com.hazelcast.core.DistributedObject;
import com.hazelcast.core.DistributedObjectEvent;
import com.hazelcast.core.DistributedObjectListener;
import com.hazelcast.instance.MemberImpl;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.spi.*;
import com.hazelcast.spi.annotation.PrivateApi;
import com.hazelcast.spi.exception.DistributedObjectDestroyedException;
import com.hazelcast.util.ConcurrencyUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.concurrent.*;
import java.util.logging.Level;

import static com.hazelcast.core.DistributedObjectEvent.EventType.CREATED;
import static com.hazelcast.core.DistributedObjectEvent.EventType.DESTROYED;
import static com.hazelcast.util.ConcurrencyUtil.ConstructorFunction;

/**
 * @mdogan 1/11/13
 */
public class ProxyServiceImpl implements ProxyService, EventPublishingService<DistributedObjectEvent, Object> {

    static final String NAME = "hz:core:proxyService";

    private final NodeEngineImpl nodeEngine;
    private final ConcurrentMap<String, ProxyRegistry> registries = new ConcurrentHashMap<String, ProxyRegistry>();
    private final Collection<DistributedObjectListener> listeners
            = Collections.newSetFromMap(new ConcurrentHashMap<DistributedObjectListener, Boolean>());
    private final ILogger logger;

    ProxyServiceImpl(NodeEngineImpl nodeEngine) {
        this.nodeEngine = nodeEngine;
        this.logger = nodeEngine.getLogger(ProxyService.class.getName());
    }

    void init() {
        nodeEngine.getEventService().registerListener(NAME, NAME, new Object());
    }

    private final ConcurrencyUtil.ConstructorFunction<String, ProxyRegistry> registryConstructor
            = new ConcurrencyUtil.ConstructorFunction<String, ProxyRegistry>() {
        public ProxyRegistry createNew(String serviceName) {
            return new ProxyRegistry(serviceName);
        }
    };

    public DistributedObject getDistributedObject(String serviceName, Object objectId) {
        ProxyRegistry registry = ConcurrencyUtil.getOrPutIfAbsent(registries, serviceName, registryConstructor);
        return registry.getProxy(objectId);
    }

    public DistributedObject getDistributedObject(Class<? extends RemoteService> serviceClass, Object objectId) {
        Collection services = nodeEngine.serviceManager.getServices(serviceClass);
        for (Object service : services) {
            if (serviceClass.isAssignableFrom(service.getClass())) {
                return getDistributedObject(((RemoteService) service).getServiceName(), objectId);
            }
        }
        throw new IllegalArgumentException();
    }

    public DistributedObject getDistributedObjectForClient(String serviceName, Object objectId) {
        ProxyRegistry registry = ConcurrencyUtil.getOrPutIfAbsent(registries, serviceName, registryConstructor);
        return registry.getClientProxy(objectId);
    }

    public void destroyDistributedObject(String serviceName, Object objectId) {
        Collection<MemberImpl> members = nodeEngine.getClusterService().getMemberList();
        Collection<Future> calls = new ArrayList<Future>(members.size());
        for (MemberImpl member : members) {
            if (member.localMember()) continue;

            Invocation inv = nodeEngine.getOperationService().createInvocationBuilder(NAME,
                    new DistributedObjectDestroyOperation(serviceName, objectId), member.getAddress())
                    .setTryCount(10).build();
            calls.add(inv.invoke());
        }
        destroyLocalDistributedObject(serviceName, objectId);
        for (Future f : calls) {
            try {
                f.get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.log(Level.FINEST, e.getMessage(), e);
            }
        }
        ProxyRegistry registry = registries.get(serviceName);
        if (registry != null) {
            registry.destroyProxy(objectId);
        }
    }

    @PrivateApi
    public void destroyLocalDistributedObject(String serviceName, Object objectId) {
        final RemoteService service = nodeEngine.serviceManager.getService(serviceName);
        if (service != null) {
            service.destroyDistributedObject(objectId);
        }
        nodeEngine.waitNotifyService.cancelWaitingOps(serviceName, objectId,
                new DistributedObjectDestroyedException(serviceName, objectId));
    }

    public Collection<DistributedObject> getDistributedObjects(String serviceName) {
        Collection<DistributedObject> objects = new LinkedList<DistributedObject>();
        ProxyRegistry registry = registries.get(serviceName);
        if (registry != null) {
            objects.addAll(registry.proxies.values());
        }
        return objects;
    }

    public Collection<DistributedObject> getAllDistributedObjects() {
        Collection<DistributedObject> objects = new LinkedList<DistributedObject>();
        for (ProxyRegistry registry : registries.values()) {
            objects.addAll(registry.proxies.values());
        }
        return objects;
    }

    public void addProxyListener(DistributedObjectListener distributedObjectListener) {
        listeners.add(distributedObjectListener);
    }

    public void removeProxyListener(DistributedObjectListener distributedObjectListener) {
        listeners.remove(distributedObjectListener);
    }

    public void dispatchEvent(final DistributedObjectEvent event, Object ignore) {
        final String serviceName = event.getServiceName();
        final ProxyRegistry registry = registries.get(serviceName);
        if (event.getEventType() == CREATED) {
            if (registry == null || !registry.contains(event.getObjectId())) {
                for (DistributedObjectListener listener : listeners) {
                    listener.distributedObjectCreated(event);
                }
            }
        } else {
            if (registry != null) {
                registry.removeProxy(event.getObjectId());
            }
            for (DistributedObjectListener listener : listeners) {
                listener.distributedObjectDestroyed(event);
            }
        }
    }

    private class ProxyRegistry {

        final RemoteService service;

        final ConcurrentMap<Object, DistributedObject> proxies = new ConcurrentHashMap<Object, DistributedObject>();
        final ConcurrentMap<Object, DistributedObject> clientProxies = new ConcurrentHashMap<Object, DistributedObject>();

        private ProxyRegistry(String serviceName) {
            this.service = nodeEngine.serviceManager.getService(serviceName);
            if (service == null) {
                throw new IllegalArgumentException("Unknown service: " + serviceName);
            }
        }

        DistributedObject getProxy(Object objectId) {
            DistributedObject proxy = proxies.get(objectId);
            if (proxy == null) {
                proxy = service.createDistributedObject(objectId);
                DistributedObject current = proxies.putIfAbsent(objectId, proxy);
                if (current == null) {
                    final DistributedObjectEvent event = createEvent(objectId, CREATED);
                    publish(event);
                    nodeEngine.getEventService().executeEvent(new Runnable() {
                        public void run() {
                            for (DistributedObjectListener listener : listeners) {
                                listener.distributedObjectCreated(event);
                            }
                        }
                    });
                } else {
                    proxy = current;
                }
            }
            return proxy;
        }

        final ConstructorFunction<Object, DistributedObject> clientProxyConstructor = new ConstructorFunction<Object, DistributedObject>() {
            public DistributedObject createNew(Object key) {
                return service.createDistributedObjectForClient(key);
            }
        };

        DistributedObject getClientProxy(Object objectId) {
            // TODO: fire object created event if required!
            return ConcurrencyUtil.getOrPutIfAbsent(clientProxies, objectId, clientProxyConstructor);
        }

        void destroyProxy(Object objectId) {
            if (proxies.remove(objectId) != null) {
                final DistributedObjectEvent event = createEvent(objectId, DESTROYED);
                publish(event);
            }
        }

        void removeProxy(Object objectId) {
            proxies.remove(objectId);
            clientProxies.remove(objectId);
        }

        private void publish(DistributedObjectEvent event) {
            final EventService eventService = nodeEngine.getEventService();
            final Collection<EventRegistration> registrations = eventService.getRegistrations(NAME, NAME);
            eventService.publishEvent(NAME, registrations, event);
        }

        private DistributedObjectEvent createEvent(Object objectId, DistributedObjectEvent.EventType type) {
            final DistributedObjectEvent event = new DistributedObjectEvent(type, service.getServiceName(), objectId);
            event.setHazelcastInstance(nodeEngine.getNode().hazelcastInstance);
            return event;
        }

        private boolean contains(Object objectId) {
            return proxies.containsKey(objectId);
        }

        void destroy() {
            proxies.clear();
            clientProxies.clear();
        }
    }

    public static class DistributedObjectDestroyOperation extends AbstractOperation {

        private String serviceName;
        private Object objectId;

        public DistributedObjectDestroyOperation() {
        }

        public DistributedObjectDestroyOperation(String serviceName, Object objectId) {
            this.serviceName = serviceName;
            this.objectId = objectId;
        }

        public void run() throws Exception {
            ProxyServiceImpl proxyService = getService();
            ProxyRegistry registry = proxyService.registries.get(serviceName);
            if (registry != null) {
                registry.removeProxy(objectId);
            }
            proxyService.destroyLocalDistributedObject(serviceName, objectId);
        }

        public boolean returnsResponse() {
            return true;
        }

        public Object getResponse() {
            return Boolean.TRUE;
        }

        @Override
        protected void writeInternal(ObjectDataOutput out) throws IOException {
            super.writeInternal(out);
            out.writeUTF(serviceName);
            out.writeObject(objectId);
        }

        @Override
        protected void readInternal(ObjectDataInput in) throws IOException {
            super.readInternal(in);
            serviceName = in.readUTF();
            objectId = in.readObject();
        }
    }

    void shutdown() {
        for (ProxyRegistry registry : registries.values()) {
            registry.destroy();
        }
        registries.clear();
        listeners.clear();
    }
}
