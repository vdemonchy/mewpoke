package com.criteo.nosql.mewpoke.memcached;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

import com.criteo.nosql.mewpoke.discovery.Dns;
import com.criteo.nosql.mewpoke.discovery.IDiscovery;
import com.criteo.nosql.mewpoke.discovery.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.criteo.nosql.mewpoke.config.Config;
import com.criteo.nosql.mewpoke.discovery.Consul;

public abstract class MemcachedRunnerAbstract implements AutoCloseable, Runnable {

    private final Logger logger = LoggerFactory.getLogger(MemcachedRunnerAbstract.class);

    private final Config cfg;
    private final long tickRate;
    private final IDiscovery discovery;

    protected Map<Service, Set<InetSocketAddress>> services;
    protected Map<Service, Optional<MemcachedMonitor>> monitors;
    protected Map<Service, MemcachedMetrics> metrics;
    private long refreshConsulInMs;

    public MemcachedRunnerAbstract(Config cfg) {
        this.cfg = cfg;
        this.discovery = buildDiscovery(cfg.getDiscovery());

        this.tickRate = Long.parseLong(cfg.getApp().getOrDefault("tickRateInSec", "20")) * 1000L;
        this.refreshConsulInMs = cfg.getDiscovery().getRefreshEveryMin() * 60 * 1000L;
        this.monitors = Collections.emptyMap();
        this.metrics = Collections.emptyMap();
        this.services = Collections.emptyMap();
    }

    //TODO: externalize in a Builder
    private IDiscovery buildDiscovery(Config.Discovery discovery) {
        Config.ConsulDiscovery consulCfg = discovery.getConsul();
        Config.StaticDiscovery staticCfg = discovery.getStaticDns();
        if (consulCfg != null) {
            logger.info("Consul configuration will be used");
            return new Consul(consulCfg.getHost(), consulCfg.getPort(),
                    consulCfg.getTimeoutInSec(), consulCfg.getReadConsistency(),
                    consulCfg.getTags());
        }
        if (staticCfg != null) {
            logger.info("Static configuration will be used");
            return new Dns(cfg.getService().getUsername(), cfg.getService().getPassword(), staticCfg.getHost(), staticCfg.getClustername());
        }
        logger.error("Bad configuration, no discovery provided");
        throw new RuntimeException("Bad configuration, no discovery provided"); //TODO: Should break the main loop here
    }

    @Override
    public void run() {

        List<EVENT> evts = Arrays.asList(EVENT.UPDATE_TOPOLOGY, EVENT.WAIT, EVENT.POKE);
        EVENT evt;
        long start, stop;

        for (; ; ) {
            start = System.currentTimeMillis();
            evt = evts.get(0);
            dispatch_events(evt);
            stop = System.currentTimeMillis();
            logger.info("{} took {} ms", evt, stop - start);

            resheduleEvent(evt, start, stop);
            Collections.sort(evts, Comparator.comparingLong(event -> event.nexTick));
        }
    }

    private void resheduleEvent(EVENT lastEvt, long start, long stop) {
        long duration = stop - start;
        if (duration >= tickRate) {
            logger.warn("Operation took longer than 1 tick, please increase tick rate if you see this message too often");
        }

        EVENT.WAIT.nexTick = start + tickRate - 1;
        switch (lastEvt) {
            case WAIT:
                break;

            case UPDATE_TOPOLOGY:
                lastEvt.nexTick = start + refreshConsulInMs;
                break;

            case POKE:
                lastEvt.nexTick = start + tickRate;
                break;
        }
    }

    public void dispatch_events(EVENT evt) {
        switch (evt) {
            case WAIT:
                try {
                    Thread.sleep(Math.max(evt.nexTick - System.currentTimeMillis(), 0));
                } catch (Exception e) {
                    logger.error("thread interrupted {}", e);
                }
                break;

            case UPDATE_TOPOLOGY:
                Map<Service, Set<InetSocketAddress>> new_services = discovery.getServicesNodesFor();

                // Consul down ?
                if (new_services.isEmpty()) {
                    logger.info("Consul sent back no services to monitor. is it down ? Are you sure of your tags ?");
                    break;
                }

                // Check if topology has changed
                if (IDiscovery.areServicesEquals(services, new_services))
                    break;

                logger.info("Topology changed, updating it");
                // Clean old monitors
                monitors.values().forEach(mo -> mo.ifPresent(MemcachedMonitor::close));
                metrics.values().forEach(MemcachedMetrics::close);

                // Create new ones
                services = new_services;
                monitors = services.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> MemcachedMonitor.fromNodes(e.getKey(), e.getValue(),
                                cfg.getService().getTimeoutInSec() * 1000L)
                        ));

                metrics = new HashMap<>(monitors.size());
                for (Map.Entry<Service, Optional<MemcachedMonitor>> client : monitors.entrySet()) {
                    metrics.put(client.getKey(), new MemcachedMetrics(client.getKey()));
                }
                break;

            case POKE:
                poke();
                break;
        }
    }

    abstract protected void poke();

    @Override
    public void close() throws Exception {
        discovery.close();
        monitors.values().forEach(mo -> mo.ifPresent(m -> {
            try {
                m.close();
            } catch (Exception e) {
                logger.error("Error when releasing resources", e);
            }
        }));
        metrics.values().forEach(MemcachedMetrics::close);
    }

    private enum EVENT {
        UPDATE_TOPOLOGY(System.currentTimeMillis()),
        WAIT(System.currentTimeMillis()),
        POKE(System.currentTimeMillis());

        public long nexTick;

        EVENT(long nexTick) {
            this.nexTick = nexTick;
        }

    }
}
